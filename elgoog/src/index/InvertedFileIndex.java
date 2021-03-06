package index;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map.Entry;

import org.apache.commons.io.IOUtils;
import org.json.JSONObject;

import cluster.DocumentVectorFactory;
import compression.EmptyCompressor;
import compression.VByteEncoder;
import reader.Document;

/* This is the InvertedFile-Index class.
 * This class can be used to:
 * 1) create an in-memory index from a document store and write that to disk
 * 2) create an in-memory index from a file on disk.
 */

public class InvertedFileIndex extends Index {

    // the inverted index file on disk
    private RandomAccessFile binaryFile = null;

    // filename where the index will be written or will be read from
    private String indexFileNameString = null;

    // list of inverted-lists for every term in the vocab
    private HashMap<String, InvertedList> invListLookup;

    // This map will be loaded from the lookup file
    // when you want to reconstruct the index from disk.
    // note that the offset here is where the list for a term BEGINS
    private LinkedHashMap<String, Integer> termToOffsetMap = null;

    // Map of term to Document-frequency
    // will be constructed from the index file on disk
    private HashMap<String, Integer> termtoDFMap = null;

    // Map of term to Collection-frequency
    // will be constructed from the index file on disk
    private HashMap<String, Integer> termtoCFMap = null;

    // Map of term to the number of bytes to read for this term
    private HashMap<String, Integer> termToReadBytesMap = null;

    private ArrayList<String> backingDocumentIDs = null;

    // number of documents in the collection. This will be written to
    // and read from the .metadata file
    private int numDocs = 0;

    // Document-vector factory of this index for clustering
    DocumentVectorFactory documentVectorFactory = null;

    // the file to load the priors from
    private String priorFile = null;

    public InvertedFileIndex(String filename) {
        super();
        invListLookup = new HashMap<String, InvertedList>();
        indexFileNameString = filename;
    }

    public void createIndexFromDocumentStore(ArrayList<Document> docs) {
        // write the number of docs into a metadata file
        PrintWriter pw = null;
        try {
            pw = new PrintWriter(indexFileNameString + ".metadata");
        } catch (FileNotFoundException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        pw.write(docs.size() + "\n");

        documentVectorFactory = new DocumentVectorFactory();

        for (Document doc : docs) {
            // write the backing document id of each doc into a separate line
            pw.write(doc.getBackingId() + "\n");
            String[] termVector = doc.getTermVector();
            int termPosition = 1;
            InvertedList list = null;
            for (String term : termVector) {
                // check if invertedList for this term exists
                if (invListLookup.containsKey(term)) {
                    // there is a list already in our index for this term
                    list = invListLookup.get(term); // index of the list
                } else {
                    list = new InvertedList(term);
                }
                list.addPositionToPosting(doc.getDocumentUniqueId(), termPosition);
                invListLookup.put(term, list);
                termPosition++;

                // add into the document vector of this document
                documentVectorFactory.addTermToDocumentVector(doc.getDocumentUniqueId(), term);
            }
        }

        // close the metadata file
        pw.close();
    }

    private long writeToBinaryFile(ArrayList<Integer> list, boolean compress) {

        byte[] toWrite = null;

        if (compress) {
            VByteEncoder compressor = new VByteEncoder();
            toWrite = compressor.encodeIntegerList(list);
        } else {
            EmptyCompressor compressor = new EmptyCompressor();
            toWrite = compressor.encodeIntegerList(list);
        }

        try {
            binaryFile.write(toWrite);
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        return toWrite.length;
    }

    private void writeLookupEntry(PrintWriter writer, String key, long length, int df, int cf) {
        writer.println(key + " " + length + " " + df + " " + cf);
    }

    public void writeSelfToDisk(boolean compress) {

        // open RAF and lookup-table writer
        try {
            binaryFile = new RandomAccessFile(indexFileNameString, "rw");
            // the offset files have .ttol extension
            PrintWriter termToOffsetLookupFile = new PrintWriter(indexFileNameString + ".ttol");

            // move raf to the beginning of the file every time before writing an index
            binaryFile.seek(0);
            binaryFile.setLength(0); // truncate any existing content

            if (compress)
                binaryFile.write('C');
            else
                binaryFile.write('U');

            long totalBytesWritten = binaryFile.length();

            for (Entry<String, InvertedList> list : invListLookup.entrySet()) {
                InvertedList temp = list.getValue();

                // write this term's offset in the index into the lookup table
                writeLookupEntry(termToOffsetLookupFile, list.getKey(), totalBytesWritten,
                        temp.getDocumentFrequency(), temp.getCollectionFrequency());

                totalBytesWritten += writeToBinaryFile(temp.getList(compress), compress);
            }

            // System.out.println("Total bytes written to disk: " + totalBytesWritten);

            // close all files on disk
            binaryFile.close();
            binaryFile = null;
            termToOffsetLookupFile.close();
            termToOffsetLookupFile = null;

        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    /*
     * This API reconstructs a completely in-memory index from the index-file on
     * disk. ONLY FOR VALIDATION PURPOSES. Retrieval doesn't use this API!
     */
    private InvertedList constructInvertedListFromByteArray(boolean compressed, byte[] buffer,
            String term) {

        ArrayList<Integer> list = null;
        InvertedList invertedList = new InvertedList(term);

        if (compressed) {
            VByteEncoder vByteDecoder = new VByteEncoder();
            list = vByteDecoder.decodeIntegerList(buffer);
        } else {
            EmptyCompressor emptyDecoder = new EmptyCompressor();
            list = emptyDecoder.decodeIntegerList(buffer);
        }

        int index, len = list.size();
        // System.out.println(list);
        int prevDocId = 0;
        for (index = 0; index < len;) {
            int docId = list.get(index);

            if (compressed) {
                // delta decoding of docId
                docId += prevDocId;
                prevDocId = docId;
            }
            index++;

            // get tf, which is not delta-encoded
            int tf = list.get(index);
            index++;
            int prevPosition = 0;
            for (int i = 0; i < tf; i++) {
                int position = list.get(index);
                index++;
                if (compressed) {
                    // delta decoding
                    position += prevPosition;
                    prevPosition = position;
                }

                invertedList.addPositionToPosting(docId, position);
            }
        }

        return invertedList;
    }

    // This method loads the lookup-table.
    // The lookup-table completely resides in memory.
    private void loadLookupTable() {
        if (termToOffsetMap == null) {
            try {
                BufferedReader termToOffsetLookupFile = new BufferedReader(
                        new FileReader(indexFileNameString + ".ttol"));

                // construct the term-offset lookup table first
                // first add the entries from the file into a List.
                String line;
                List<Entry<String, Integer>> list = new ArrayList<Entry<String, Integer>>();
                termtoDFMap = new HashMap<String, Integer>();
                termtoCFMap = new HashMap<String, Integer>();
                termToReadBytesMap = new HashMap<String, Integer>();

                while ((line = termToOffsetLookupFile.readLine()) != null) {
                    String[] terms = line.split("\\s+");
                    int offset = Integer.valueOf(terms[1]);
                    int df = Integer.valueOf(terms[2]); // document frequency
                    int cf = Integer.valueOf(terms[3]); // collection-frequency
                    list.add(new SimpleEntry<String, Integer>(terms[0], offset));
                    termtoDFMap.put(terms[0], df);
                    termtoCFMap.put(terms[0], cf);
                }

                // at this point, the List has entries in sorted order of offset-values
                // we now create a LinkedHashMap out of this list.
                // LinkedHashMap is used so that we fix the order
                // of keys unlike HashMap which has arbitrary order.
                termToOffsetMap = new LinkedHashMap<String, Integer>();

                // put in first entry's start offset
                termToOffsetMap.put(list.get(0).getKey(), list.get(0).getValue());

                int i, bytesToRead;
                Entry<String, Integer> curEntry, prevEntry = list.get(0);
                for (i = 1; i < list.size(); i++) {
                    curEntry = list.get(i);
                    termToOffsetMap.put(curEntry.getKey(), curEntry.getValue());
                    bytesToRead = curEntry.getValue() - prevEntry.getValue();
                    termToReadBytesMap.put(prevEntry.getKey(), bytesToRead);
                    prevEntry = curEntry;
                }

                // compute how many bytes to read for the last erm
                // this should be the 'length of the index file' - 'start offset of term in the
                // index' + 1
                binaryFile = new RandomAccessFile(indexFileNameString, "r");
                bytesToRead = (int) (binaryFile.length() - prevEntry.getValue());
                termToReadBytesMap.put(prevEntry.getKey(), bytesToRead);

                binaryFile.close();
                binaryFile = null;
                termToOffsetLookupFile.close();
                termToOffsetLookupFile = null;

                // System.out.println(termToOffsetMap);
                // System.out.println(termToReadBytesMap);

                // load the metadata file to find out the number of docs in the collection.
                // the first line has that info.
                // the metadata file will be named (and has to be)
                // index's file-name + ".metadata" extension.
                BufferedReader metadataReader = new BufferedReader(
                        new FileReader(indexFileNameString + ".metadata"));
                numDocs = Integer.valueOf(metadataReader.readLine());

                line = null;
                backingDocumentIDs = new ArrayList<String>();
                while ((line = metadataReader.readLine()) != null) {
                    backingDocumentIDs.add(line);
                }

                metadataReader.close();

            } catch (NumberFormatException | IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
    }

    public void createCompleteIndexFromDisk() {
        // open RAF and lookup-table reader
        try {
            loadLookupTable();

            // at this point, we have map of term to offset/df/cf/bytesToRead
            // we can start creating the in-memory index from the the index file
            binaryFile = new RandomAccessFile(indexFileNameString, "r");
            binaryFile.seek(0);

            // read the first byte to find out if this is uncompressed or compressed index
            boolean compressed = (binaryFile.readByte() == 'C');

            for (Entry<String, Integer> entry : termToOffsetMap.entrySet()) {
                String term = entry.getKey();

                // look up the termToReadBytesMap table to find how may bytes to
                // read for this term.
                int bytesToRead = termToReadBytesMap.get(term);
                byte[] buffer = new byte[bytesToRead];
                binaryFile.read(buffer, 0, bytesToRead);

                InvertedList l = constructInvertedListFromByteArray(compressed, buffer, term);
                // l.printSelf();
                invListLookup.put(term, l);
            }

            binaryFile.close();
            binaryFile = null;

        } catch (FileNotFoundException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

    }

    public void printSelf() {
        for (Entry<String, InvertedList> entry : invListLookup.entrySet()) {
            entry.getValue().printSelf();
        }
    }

    private HashMap<String, InvertedList> getInvertedLists() {
        return invListLookup;
    }

    public static boolean compareTwoInvertedIndexes(InvertedFileIndex index1,
            InvertedFileIndex index2) {

        // this is just a quick check
        int size1 = index1.getInvertedLists().size();
        int size2 = index2.getInvertedLists().size();

        if (size1 != size2)
            return false;

        for (Entry<String, InvertedList> entry : index1.getInvertedLists().entrySet()) {
            String key = entry.getKey();
            if (!InvertedList.compareTwoInvertedLists(entry.getValue(),
                    index2.getInvertedLists().get(key))) {
                return false;
            }
        }

        System.out.println(
                "All terms in both indexes have the same InvertedLists and the associated Postings.");

        return true;
    }

    // Reads the index File and gets the InvertedList for a term
    public InvertedList getInvertedListForTerm(String term) {

        boolean compressed = false;

        // load the lookup table if not already done
        if (termToOffsetMap == null) {
            loadLookupTable();
        }

        try {
            if (binaryFile == null)
                binaryFile = new RandomAccessFile(indexFileNameString, "r");
            binaryFile.seek(0);

            // read the first byte to find out if this is uncompressed or compressed index
            compressed = (binaryFile.readByte() == 'C');

        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        // look up the termToReadBytesMap table to find how may bytes to
        // read for this term.
        if (!termToOffsetMap.containsKey(term)) {
            // term not present in index
            return null;
        }

        int bytesToRead = termToReadBytesMap.get(term);
        byte[] buffer = new byte[bytesToRead];

        try {
            binaryFile.seek(termToOffsetMap.get(term));
            binaryFile.read(buffer, 0, bytesToRead);
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        return constructInvertedListFromByteArray(compressed, buffer, term);
    }

    @Override
    public ArrayList<String> getVocabListFromIndex() {
        ArrayList<String> result = new ArrayList<String>();

        // load the lookup table if not already done
        if (termToOffsetMap == null) {
            loadLookupTable();
        }

        // return the keys from the lookup-table
        for (String s : termToOffsetMap.keySet()) {
            result.add(s);
        }
        return result;
    }

    // returns frequency of a term over the entire corpus
    @Override
    public int getCollectionFrequencyForTerm(String term) {

        // load the lookup table if not already done
        if (termToOffsetMap == null) {
            loadLookupTable();
        }

        if (termtoCFMap.containsKey(term))
            return termtoCFMap.get(term);

        return 0;
    }

    // return how many documents does the term appear in atleast once
    @Override
    public int getDocumentFrequencyForTerm(String term) {

        // load the lookup table if not already done
        if (termToOffsetMap == null) {
            loadLookupTable();
        }

        if (termtoDFMap.containsKey(term))
            return termtoDFMap.get(term);

        return 0;
    }

    @Override
    public int getNumDocs() {

        // load the lookup table if not already done
        // this will read the .metadata file as well which has
        // the total number of docs in the collection
        if (termToOffsetMap == null) {
            loadLookupTable();
        }
        return numDocs;
    }

    @Override
    public int getNumWordsInCollection() {
        // load the lookup table if not already done
        if (termToOffsetMap == null) {
            loadLookupTable();
        }

        int count = 0;
        for (Entry<String, Integer> entry : termtoCFMap.entrySet()) {
            count += entry.getValue();
        }

        return count;
    }

    @Override
    public int getNumWordsInDocument(int docId) {

        // load the lookup table if not already done
        if (termToOffsetMap == null) {
            loadLookupTable();
        }

        int count = 0;
        for (String term : getVocabListFromIndex()) {
            HashMap<Integer, Posting> posting = getInvertedListForTerm(term).getPostings();
            if (posting.containsKey(docId)) {
                count += posting.get(docId).getTermFrequency();
            }
        }

        return count;
    }

    @Override
    public ArrayList<String> getBackingDocumentIDs() {
        // load the lookup table if not already done
        if (termToOffsetMap == null) {
            loadLookupTable();
        }

        return backingDocumentIDs;
    }

    public void writeDocumentVectorsToJSON() {
        assert documentVectorFactory != null;

        // write to index's path with a ".docvec.json" extension
        String filename = indexFileNameString + ".docvec.json";

        JSONObject jsonObject = documentVectorFactory.JSONifySelf();

        PrintWriter pw;
        try {
            pw = new PrintWriter(filename);
            pw.write(jsonObject.toString(2));
            pw.close();
        } catch (FileNotFoundException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    public DocumentVectorFactory getDocumentVectorsFromJSON() {

        if (documentVectorFactory == null) {
            documentVectorFactory = new DocumentVectorFactory();
            InputStream is;
            String filename = indexFileNameString + ".docvec.json";
            System.out.println(filename);
            try {
                is = new FileInputStream(filename);
                String jsonTxt = IOUtils.toString(is, "UTF-8");
                JSONObject jsonObject = new JSONObject(jsonTxt);

                for (String scene : jsonObject.keySet()) {
                    int docId = Integer.parseInt(scene);
                    JSONObject bagOfWords = (JSONObject) jsonObject.get(scene);
                    for (String term : bagOfWords.keySet()) {
                        int termFrequency = bagOfWords.getInt(term);
                        documentVectorFactory.addTermToDocumentVector(docId, term, termFrequency);
                    }
                }
            } catch (FileNotFoundException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }

        return documentVectorFactory;
    }

    @Override
    public Double getPriorForDocument(int docId, String priorFile) {

        Double priorValue = null;

        try {
            RandomAccessFile priorReader = new RandomAccessFile(priorFile, "r");

            // each document's prior value is in a line ordered by the document-id
            // seek to offset in the file and just read 8 bytes
            // i.e. the size of a double value
            priorReader.seek(docId * 8);
            priorValue = priorReader.readDouble();
            priorReader.close();
        } catch (Exception e) {
            e.printStackTrace();
        }

        return priorValue;
    }

}
