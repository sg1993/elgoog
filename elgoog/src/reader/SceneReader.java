package reader;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;

import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.json.JSONObject;

public class SceneReader extends Reader {

    private JSONObject jsonObject = null;

    public SceneReader(String fname) {
        super(fname);
    }

    @Override
    public void read() {
        InputStream is;
        try {
            is = new FileInputStream(this.filename);
            String jsonTxt = IOUtils.toString(is, "UTF-8");
            jsonObject = new JSONObject(jsonTxt);

            // directly go for the "corpus" key
            JSONArray corpus = (JSONArray) jsonObject.get("corpus");
            Iterator<Object> corpusIterator = corpus.iterator();

            while (corpusIterator.hasNext()) {
                // we are going through each scene in the corpus.
                JSONObject scene = (JSONObject) corpusIterator.next();

                String playId = (String) scene.get("playId");
                String sceneId = (String) scene.get("sceneId");
                int sceneNum = (int) scene.get("sceneNum");
                String sceneText = (String) scene.get("text");

                Scene currentScene = new Scene(playId, sceneId, sceneNum, sceneText);
                putIntoDocumentList(currentScene);
            }
        } catch (FileNotFoundException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

    }

    public void getNextDocumentId() {
        if (filename == null) {
            throw new IllegalStateException("You haven't specified which JSON file to read\n");
        }
    }
}
