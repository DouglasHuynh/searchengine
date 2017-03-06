package cs121.index;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import javax.annotation.PostConstruct;
import org.apache.http.HttpEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.nio.entity.NStringEntity;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import cs121.config.WebPageSettings;
import io.undertow.util.FileUtils;

public class Indexer {

    public static final Logger LOG = LoggerFactory.getLogger(Indexer.class);

    RestClient client;

    @Autowired
    WebPageSettings webPageSettings;
    @Autowired
    Environment env;
    Gson gson;

    public Indexer(RestClient client) {
        this.client = client;
        this.gson = new Gson();
    }

    @PostConstruct
    public void init() {
        if (env.getProperty("run_index").equals("false")) {
            LOG.warn("Skipping indexing...");
            return;
        }

        try {
            String content = FileUtils.readFile(
                    Paths.get(webPageSettings.getDir(), webPageSettings.getJson()).toAbsolutePath().toUri().toURL());
            Type type = new TypeToken<Map<String, String>>() {
            }.getType();
            Map<String, String> urlMap = gson.fromJson(content, type);
            File root = new File(webPageSettings.getDir());
            indexFiles(root, root.getName(), urlMap);
        }

        catch (IOException e) {
            LOG.error("Could not connect URLs", e);
        }
        LOG.info(webPageSettings.getDir());
    }

    public void indexFiles(File root, String name, Map<String, String> map) {

        if (!root.exists()) {
            LOG.warn("File {} does not exist.", root.getName());
            return;
        }

        if (root.isDirectory()) {
            for (File file : root.listFiles())
                indexFiles(file, root.getName(), map);
        }
        else if (root.isFile()) {
            parseAndIndex(root, name, map);
        }
        else {
            LOG.warn("{} is neither a directory nor file.", root.getName());
        }
    }

    private void parseAndIndex(File file, String dirName, Map<String, String> map) {
        try {
            Document doc = Jsoup.parse(file, "ISO-8859-1");
            String url = map.get(dirName + "/" + file.getName());
            
//            //Iterate through all possible tags
//            Elements elements = doc.select("*");
//            for (Element e : elements) {
//            	if (!e.text().isEmpty())
//            		LOG.info(e.tagName() + ": " + e.text());
//            }
            
            Elements headers = doc.select("h1, h2, h3, h4, h5, h6");
            Element title = doc.select("title").first();
            Elements bold = doc.select("B");
            Elements italic = doc.select("I");
            Element body = doc.body();
            Elements links = doc.select("a");

            if (headers == null || title == null || bold == null || body == null || italic == null || links == null)
                return;
            
            String[] headerTokens = headers.text().split("[^\\w']+");
            String[] titleTokens = title.text().split("[^\\w']+");
            String[] boldTokens = bold.text().split("[^\\w']+");
            String[] italicTokens = italic.text().split("[^\\w']+");
            String[] bodyTokens = body.text().split("[^\\w']+");
            String[] linkTokens = links.text().split("[^\\w']+");
            
            List<String> tokens = new ArrayList<String>();
            tokens.addAll(Arrays.asList(headerTokens));
            tokens.addAll(Arrays.asList(titleTokens));
            tokens.addAll(Arrays.asList(boldTokens));
            tokens.addAll(Arrays.asList(italicTokens));
            tokens.addAll(Arrays.asList(bodyTokens));
            tokens.addAll(Arrays.asList(linkTokens));
            
            JsonObject rootObj = new JsonObject();
            JsonArray jsonArray = new JsonArray();
            JsonArray titleArray = new JsonArray();
            
            for (String t : titleTokens) {
            	if (!t.isEmpty())
            		titleArray.add(t);
            }
            
            for (String token : tokens) {
            	if (!token.isEmpty())
            		jsonArray.add(token.toLowerCase());
            }
                     
            rootObj.addProperty("url", url);
            rootObj.add("title", titleArray);
            rootObj.add("tokens", jsonArray);
            
            HttpEntity entity = new NStringEntity(rootObj.toString(), ContentType.APPLICATION_JSON);
            
            Response response = client.performRequest("PUT",
                    "html_index/" + dirName + "/" + file.getName(),
                    Collections.<String, String> emptyMap(), entity);
                        
            try {
                Thread.sleep(100);
            }
            catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        
        catch (ElasticsearchException e) {
        	LOG.error("ElasticsearchException thrown {}", e);
        }
        
        catch (IOException e) {
            LOG.error("Could not open file {}", dirName + "/" + file.getName());
            return;
        }
    }
    
    public void computeWordFrequency(String[] tokens, HashMap<String, Integer> map) {
    	for (String token : tokens) {
    		
    		token = token.toLowerCase();
    		Integer found = map.get(token);
    		
    		if (found == null) {
    			map.put(token, 1);
    		}
         	else {
         		map.put(token, found + 1);
         	}
    	}
    }
}
