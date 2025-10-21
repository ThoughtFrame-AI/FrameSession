package org.thoughtframe.llm.llama;

import java.io.File;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.openedit.OpenEditException;
import org.openedit.WebPageRequest;
import org.openedit.page.Page;
import org.openedit.util.Exec;
import org.openedit.util.ExecResult;
import org.thoughtframe.llm.LLMResponse;
import org.thoughtframe.testbench.TestBench;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * LlamaServer — wraps a single running llama.cpp instance with REST API access.
 * Each model/port combination corresponds to one LlamaServer.
 */
public class LlamaServer {

    private static final Log log = LogFactory.getLog(LlamaServer.class);

    protected final TestBench fieldTestBench;
    protected final String modelPath;
    public TestBench getTestBench()
	{
		return fieldTestBench;
	}

	public String getModelPath()
	{
		return modelPath;
	}

	public int getPort()
	{
		return port;
	}

	protected final int port;
    protected int slots = 2;

    public LlamaServer(TestBench inBench, String inModelPath, int inPort) {
        fieldTestBench = inBench;
        this.modelPath = inModelPath;
        this.port = inPort;
    }

    // -------------------------------------------------------------
    // ---- Process control
    // -------------------------------------------------------------

    public synchronized void start() {
        Exec exec = (Exec) getTestBench().getBean("exec");
        Page binfolder = getTestBench().getPageManager().getPage("/WEB-INF/bin/linux");
        File binDir = new File(binfolder.getContentItem().getAbsolutePath());
        Page sessions = getTestBench().getPageManager().getPage("/WEB-INF/data/" + getTestBench().getCatalogId() + "/framesessions/");
        File folder = new File(sessions.getContentItem().getAbsolutePath());
        folder.mkdirs();
        List<String> cmd = new ArrayList<>();
        cmd.add("./llama-server");
        cmd.add("--ctx-size");
        cmd.add("32000");
        cmd.add("-m");
        cmd.add(modelPath);
        cmd.add("--host");
        cmd.add("127.0.0.1");
        cmd.add("--port");
        cmd.add(String.valueOf(port));
        cmd.add("--slot-save-path");
        cmd.add(sessions.getContentItem().getAbsolutePath());
        cmd.add("--n-predict");
        cmd.add("64");
        cmd.add("--threads");
        cmd.add("16");
       
       


        log.info("🚀 Starting llama-server on port " + port + " model=" + modelPath);
        ExecResult res = exec.runExec(cmd, binDir, false, -1L, true);
        if (!res.isRunOk()) {
        	log.info(res.getStandardOut());
        	log.info(res.getStandardError());
            throw new OpenEditException("Failed to start llama-server: " + res.getStandardError());
        }
    }

    public boolean isAlive() {
        OkHttpClient client = getTestBench().getHttpClient();
        String url = "http://localhost:" + port + "/health";
        try (Response resp = client.newCall(new Request.Builder().url(url).get().build()).execute()) {
            return resp.isSuccessful();
        } catch (Exception e) {
            return false;
        }
    }

//    public synchronized void stop() {
//        Exec exec = (Exec) bench.getBean("exec");
//        try {
//            exec.killProcess("llama-server");
//            log.info("🛑 llama-server stopped on port " + port);
//        } catch (Exception e) {
//            log.warn("Failed to stop llama-server on port " + port + ": " + e.getMessage());
//        }
//    }

    public String getBaseUrl() {
        return "http://localhost:" + port;
    }

    // -------------------------------------------------------------
    // ---- API Calls
    // -------------------------------------------------------------

    /** /api/chat */
    public LlamaResponse runPageAsInput(WebPageRequest inReq, String inModel, String inTemplate) {
        inReq.putPageValue("model", inModel);
        inReq.putPageValue("llama", this);
        inReq.putPageValue("mediaarchive", getTestBench());

		String input = getTestBench().getRender().loadInputFromTemplate(inReq, inTemplate);
        log.info("Running Llama model: " + inModel + " template: " + inTemplate);

        JSONObject obj = new JSONObject();
        obj.put("model", inModel);
        obj.put("stream", false);

        JSONArray messages = new JSONArray();
        JSONObject user = new JSONObject();
        user.put("role", "user");
        user.put("content", input);
        messages.add(user);
        obj.put("messages", messages);

        JSONObject json = postJson("/api/chat", obj);
        LlamaResponse response = new LlamaResponse();
        response.setRawResponse(json);
        return response;
    }

    /** /api/embedding */
    public String getEmbedding(String inModel, String inQuery) throws Exception {
        JSONObject req = new JSONObject();
        req.put("model", inModel);
        req.put("input", inQuery);

        JSONObject json = postJson("/api/embedding", req);
        JSONArray data = (JSONArray) json.get("embedding");
        if (data == null) {
            throw new OpenEditException("Invalid embedding response: " + json.toJSONString());
        }
        return data.toJSONString();
    }

    /** /slots/{id}?action=restore */
    public JSONObject loadSession(int slotId, String filename) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("filename", filename);
        return postJson("/slots/" + slotId + "?action=restore", payload);
    }

    /** /slots/{id}?action=save */
    public JSONObject saveSession(int slotId, String filename) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("filename", filename);
        return postJson("/slots/" + slotId + "?action=save", payload);
    }

    /** /slots/{id}?action=clear */
    public JSONObject clearSlot(int slotId) throws Exception {
        return postJson("/slots/" + slotId + "?action=erase", new JSONObject());
    }

    // -------------------------------------------------------------
    // ---- Ingestion and Preprocessing
    // -------------------------------------------------------------

    public synchronized void injest(String inModel, String inId, String inText) throws Exception {
        clearSlot(0);
        preprocessInput(0, inModel, inText);
        saveSession(0, loadSessionId(inModel, inId));
        clearSlot(0);
    }

	private String loadSessionId(String inModel, String inId)
	{
		return inId + "." + inModel + ".framesession";
	}

    protected LlamaResponse preprocessInput(int slotId, String inModel, String inContent) {
        JSONArray messages = new JSONArray();

        JSONObject system = new JSONObject();
        system.put("role", "system");
        system.put("content", "You act as a Data Archivist.");
        messages.add(system);

        JSONObject user = new JSONObject();
        user.put("role", "user");
        user.put("content", inContent);
        messages.add(user);

        JSONObject req = new JSONObject();
        req.put("slot_id", slotId);
        req.put("model", inModel);
        req.put("messages", messages);
        req.put("stream", false);

        JSONObject json = postJson("/v1/chat/completions", req);
        LlamaResponse response = new LlamaResponse();
        response.setRawResponse(json);
        return response;
    }

    // -------------------------------------------------------------
    // ---- HTTP Helper
    // -------------------------------------------------------------

    protected JSONObject postJson(String path, JSONObject payload) {
        OkHttpClient client = getTestBench().getHttpClient();
        String url = getBaseUrl() + path;

        RequestBody body = RequestBody.create(payload.toJSONString(),
                MediaType.parse("application/json; charset=utf-8"));
        Request request = new Request.Builder().url(url).header("Content-Type", "application/json").post(body).build();

        try (Response resp = client.newCall(request).execute()) {
            if (!resp.isSuccessful()) {
                String error = resp.body() != null ? resp.body().string() : "";
                throw new OpenEditException("Llama API error: " + resp.code() + " " + error);
            }

            JSONParser parser = new JSONParser();
            return (JSONObject) parser.parse(resp.body().charStream());
        } catch (Exception e) {
            throw new OpenEditException("Error calling Llama API " + path, e);
        }
    }

    public LlamaResponse querySession(String inModel, String inId, String inSeed, String inText) throws Exception {
        String sessionFile = loadSessionId(inModel, inId);

        // 1. Prepare clean in-memory slot
        clearSlot(0);

        // 2. Load the immutable pre-trained session (read-only)
        loadSession(0, sessionFile);

        // 3. Build the message sequence: seed first, then query
        JSONArray messages = new JSONArray();
        
        JSONObject system = new JSONObject();
        system.put("role", "system");
        system.put("content", "You act as a Data Archivist.");
        messages.add(system);
        
        JSONObject seedMsg = new JSONObject();
        seedMsg.put("role", "user");
        seedMsg.put("content", inSeed);
        messages.add(seedMsg);

        JSONObject queryMsg = new JSONObject();
        queryMsg.put("role", "user");
        queryMsg.put("content", inText);
        messages.add(queryMsg);

        // 4. Construct payload
        JSONObject req = new JSONObject();
        req.put("slot_id", 0);
        req.put("model", inModel);
        req.put("messages", messages);
        req.put("stream", false);

        // 5. Execute inference — strictly no writes
        JSONObject json = postJson("/v1/chat/completions", req);
        if (json.containsKey("timings")) {
            JSONObject t = (JSONObject) json.get("timings");
            log.info(String.format(
                "Llama timings → cache_n=%s | prompt_n=%s | prompt_ms=%.3f | prompt_per_token_ms=%.3f | predicted_ms=%.3f | predicted_per_token_ms=%.3f",
                t.get("cache_n"),
                t.get("prompt_n"),
                t.get("prompt_ms"),
                t.get("prompt_per_token_ms"),
                t.get("predicted_ms"),
                t.get("predicted_per_token_ms")
            ));
        }


     // log the model’s actual reply text
        if (json.containsKey("choices")) {
            JSONArray choices = (JSONArray) json.get("choices");
            if (!choices.isEmpty()) {
                JSONObject choice = (JSONObject) choices.get(0);
                JSONObject message = (JSONObject) choice.get("message");
                if (message != null && message.containsKey("content")) {
                	String content =  (String) message.get("content");
                    log.info("Llama reply → " +content);
                   if(inSeed.contains("Not Crown agent")){
                       log.info("Llama Seed → " +seedMsg);               	   
                   }
                }
            }
        }
        
        LlamaResponse response = new LlamaResponse();
        response.setRawResponse(json);

        log.info("Queried immutable session " + sessionFile + " (seed+query only)");
        return response;
    }


}
