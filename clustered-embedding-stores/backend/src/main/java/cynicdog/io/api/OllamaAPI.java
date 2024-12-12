package cynicdog.io.api;

import io.vertx.core.Vertx;
import io.vertx.core.impl.logging.Logger;
import io.vertx.core.impl.logging.LoggerFactory;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.parsetools.JsonParser;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.codec.BodyCodec;
import org.infinispan.Cache;

import static cynicdog.io.Main.POD_NAME;
import static cynicdog.io.util.VectorUtils.retrieveRelevantData;

public class OllamaAPI {

    static final Logger logger = LoggerFactory.getLogger(OllamaAPI.class);

    final String host;
    final int port;

    final Vertx vertx;
    final WebClient client;
    final Cache<String, float[]> embeddingsCache;

    public OllamaAPI(Vertx vertx, String host, int port, Cache<String, float[]> embeddingsCache) {
        this.host = host;
        this.port = port;
        this.vertx = vertx;
        this.client = WebClient.create(vertx);
        this.embeddingsCache = embeddingsCache;

        String[] models = {"mxbai-embed-large:latest", "qwen:1.8b"};

        for (String model : models) {
            client.post(11434, "localhost", "/api/pull")
                    .sendJsonObject(new JsonObject().put("model", model))
                    .onSuccess(res -> logger.info("Model " + model + " pulled."))
                    .onFailure(err -> logger.error("Embedding request failed: ", err));
        }
    }

    public void embed(RoutingContext context) {

        String prompt = context.getBodyAsJson().getString("prompt");
        client.post(11434, "localhost", "/api/embeddings")
                .sendJsonObject(new JsonObject()
                        .put("model", "mxbai-embed-large:latest")
                        .put("prompt", prompt))
                .onSuccess(res -> {

                    JsonArray embeddingsJson = res.bodyAsJsonObject().getJsonArray("embedding");

                    float[] embeddings = new float[embeddingsJson.size()];
                    for (int i = 0; i < embeddingsJson.size(); i++) {
                        embeddings[i] = embeddingsJson.getFloat(i);
                    }

                    String key = Integer.toString(prompt.hashCode());

                    // Store the embeddings in the cache
                    embeddingsCache.put(key, embeddings);

                    var message = "Embedding entry stored with key: " + key;
                    logger.info(message);
                    context.response().end(message);
                })
                .onFailure(err -> {

                    var message = "Embedding request failed: " + err.getMessage();
                    logger.error(message);
                    context.response().setStatusCode(500).end(message);
                });
    }

    public void generate(RoutingContext context) {

        String prompt = context.getBodyAsJson().getString("prompt");
        client.post(11434, "localhost", "/api/embeddings")
                .sendJsonObject(new JsonObject()
                        .put("model", "mxbai-embed-large:latest")
                        .put("prompt", prompt))
                .onSuccess(res -> {
                    JsonArray embeddingsJson = res.bodyAsJsonObject().getJsonArray("embedding");
                    float[] embeddings = new float[embeddingsJson.size()];
                    for (int i = 0; i < embeddingsJson.size(); i++) {
                        embeddings[i] = embeddingsJson.getFloat(i);
                    }

                    String data = retrieveRelevantData(embeddings, embeddingsCache);

                    context.response().setChunked(true);
                    JsonParser parser = JsonParser.newParser().objectValueMode();
                    parser.handler(event -> {

                        JsonObject json = event.objectValue();
                        String content = json.getString("response");
                        boolean done = json.getBoolean("done", false);

                        context.response().write(content);

                        if (done) {
                            context.response().end(String.format("\n\nFrom - %s", POD_NAME));
                        }
                    });
                    parser.exceptionHandler(err -> {
                        logger.error("JSON streaming failed", err);
                    });

                    client.post(11434, "localhost", "/api/generate")
                            .as(BodyCodec.jsonStream(parser))
                            .sendJsonObject(new JsonObject()
                                    .put("model", "qwen:1.8b")
                                    .put("prompt", String.format("Using this data: %s, respond to this prompt: %s", data, prompt)))
                            .onFailure(err -> {
                                logger.error("Failed to connect to Ollama", err);
                            });
                })
                .onFailure(err -> {
                    logger.error("Generate request failed: ", err);
                    context.response().setStatusCode(500).end("Failed to generate response.");
                });
    }

    public static class Embedding {
        private float[] latentScores;
        private String document;

        public Embedding(float[] latentScores) {
            this.latentScores = latentScores;
        }

        public float[] getLatentScores() {
            return latentScores;
        }

        public void setLatentScores(float[] latentScores) {
            this.latentScores = latentScores;
        }

        public String getDocument() {
            return document;
        }

        public void setDocument(String document) {
            this.document = document;
        }
    }
}