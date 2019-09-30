package main;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import index.InvertedFileIndex;
import reader.SceneReader;

public class Runner {
    public static void main(String[] args) {

        boolean createIndex = true, compressIndex = false, indexValidation = false,
                comprValidation = false;
        String indexInPath = null, indexOutPath = null, indexValidationPath = null;

        // parse the arguments using Apache-CLI
        Options options = new Options();
        options.addOption("i", true, "create index from document-store");
        options.addOption("c", false, "compress index before writing to disk");
        options.addOption("d", true, "create in-memory index from file on disk");
        options.addOption("v", true,
                "validate index created from document store vs the same index created from disk");
        options.addOption("t", "validate-compr", true,
                "validate 2 indexes from document store with and without compression");

        // automatically generate the help statement
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp(" ", options);

        CommandLineParser parser = new DefaultParser();
        try {

            CommandLine cmd = parser.parse(options, args);

            if (cmd.hasOption("i")) {
                createIndex = true;
                indexOutPath = cmd.getOptionValue("i");
            } else if (cmd.hasOption("d")) {
                createIndex = false;
                indexInPath = cmd.getOptionValue("d");
            }

            if (cmd.hasOption("c")) {
                compressIndex = true;
            }

            if (cmd.hasOption("v")) {
                indexValidation = true;
                indexValidationPath = cmd.getOptionValue("v");
            } else if (cmd.hasOption("t")) {
                comprValidation = true;
                indexValidationPath = cmd.getOptionValue("t");
            }

        } catch (ParseException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        SceneReader sceneReader = new SceneReader(
                "C:/Users/georg/motherlode/UMass/cs546/" + "shakespeare-scenes.json");
        sceneReader.read();
        System.out.println("There are " + sceneReader.getDocumentListSize() + " documents");

        // Begin indexing if the command-line parameter says so
        if (indexValidation) {
            // create an index
            InvertedFileIndex index1 = new InvertedFileIndex(indexValidationPath);
            index1.createIndexFromDocumentStore(sceneReader.getDocuments());
            // index.printSelf();
            index1.writeSelfToDisk(compressIndex);
            // index1.printSelf();

            // construct index from disk from the file written by the previous step
            InvertedFileIndex index2 = new InvertedFileIndex(indexValidationPath);
            index2.createCompleteIndexFromDisk();

            // compare index1 vs index2
            boolean same = InvertedFileIndex.compareTwoInvertedIndexes(index1, index2);
            if (!same) {
                System.out.println("Validation failed!");
            } else {
                System.out.println("Validation success!");
            }
        } else if (comprValidation) {
            // create an index without compression
            InvertedFileIndex index1 = new InvertedFileIndex(indexValidationPath + ".uncompressed");
            index1.createIndexFromDocumentStore(sceneReader.getDocuments());
            // index.printSelf();
            index1.writeSelfToDisk(false);
            // index1.printSelf();

            // create an index with compression
            InvertedFileIndex index2 = new InvertedFileIndex(indexValidationPath + ".compressed");
            index2.createIndexFromDocumentStore(sceneReader.getDocuments());
            // index.printSelf();
            index2.writeSelfToDisk(true);
            // index1.printSelf();

            // compare index1 vs index2
            boolean same = InvertedFileIndex.compareTwoInvertedIndexes(index1, index2);
            if (!same) {
                System.out.println("Validation failed!");
            } else {
                System.out.println("Validation success!");
            }

        } else if (createIndex) {
            // create an index
            InvertedFileIndex index = new InvertedFileIndex(indexOutPath);
            index.createIndexFromDocumentStore(sceneReader.getDocuments());
            // index.printSelf();
            index.writeSelfToDisk(compressIndex);
        } else {
            // construct in-memory index from file on disk
            InvertedFileIndex index = new InvertedFileIndex(indexInPath);
            index.createCompleteIndexFromDisk();
        }
    }
}
