package io.pyroscope.javaagent.impl;

import io.pyroscope.http.Format;
import io.pyroscope.javaagent.Snapshot;
import io.pyroscope.javaagent.api.Exporter;
import io.pyroscope.javaagent.api.Logger;
import io.pyroscope.javaagent.config.Config;
import io.pyroscope.labels.Pyroscope;
import okhttp3.*;

import java.io.FileOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Random;

public class PyroscopeExporter implements Exporter {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);//todo allow configuration

    private static final MediaType PROTOBUF = MediaType.parse("application/x-protobuf");

    final Config config;
    final Logger logger;
    final OkHttpClient client;

    public PyroscopeExporter(Config config, Logger logger) {
        this.config = config;
        this.logger = logger;
        this.client = new OkHttpClient.Builder()
            .connectTimeout(TIMEOUT)
            .readTimeout(TIMEOUT)
            .callTimeout(TIMEOUT)
            .retryOnConnectionFailure(true)
            .build();

    }

    public void writeToFile(byte[] data, String fileName) throws IOException{
        FileOutputStream out = new FileOutputStream(fileName);
        out.write(data);
        out.close();
    }

    @Override
    public void export(Snapshot snapshot) {
        //String dataStr = new String(snapshot.data);
        //System.out.println(dataStr);

        try {

            uploadSnapshot(snapshot);

        } catch (final InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void uploadSnapshot(final Snapshot snapshot) throws InterruptedException {
        final HttpUrl url = urlForSnapshot(snapshot);
        System.out.println(url.toString());
        final ExponentialBackoff exponentialBackoff = new ExponentialBackoff(1_000, 30_000, new Random());
        boolean success = false;
        while (!success) {
            final RequestBody requestBody;
            if (config.format == Format.JFR) {
                try {
                    writeToFile(snapshot.data, "jfr.raw");
                    byte[] labels = snapshot.labels.toByteArray();
                    writeToFile(labels, "jfr_labels.raw");
                    //System.out.println(snapshot.);
                } catch (Exception e) {
                    System.out.println(e.getMessage());
                }
                byte[] labels = snapshot.labels.toByteArray();
                logger.log(Logger.Level.DEBUG, "Upload attempt. JFR: %s, labels: %s", snapshot.data.length, labels.length);
                MultipartBody.Builder bodyBuilder = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM);
                bodyBuilder.addFormDataPart(
                    /* name */ "jfr",
                    /* filename */ "jfr",
                    RequestBody.create(snapshot.data)
                );
                if (labels.length > 0) {
                    bodyBuilder.addFormDataPart(
                        /* name */ "labels",
                        /* filename */ "labels",
                        RequestBody.create(labels, PROTOBUF)
                    );
                }
                requestBody = bodyBuilder.build();
            } else {
                logger.log(Logger.Level.DEBUG, "Upload attempt. collapsed: %s", snapshot.data.length);
                String rawStr = new String(snapshot.data);
                System.out.println(rawStr);
                System.out.println(rawStr);
                requestBody = RequestBody.create(snapshot.data);
            }
            Request.Builder request = new Request.Builder()
                .post(requestBody)
                .url(url);
            if (config.authToken != null && !config.authToken.isEmpty()) {
                request.header("Authorization", "Bearer " + config.authToken);
            }
            request.header("Accept-Encoding", "identity");
            request.header("Connection", "close");
            try (Response response = client.newCall(request.build()).execute()) {
                int status = response.code();
                if (status >= 400) {
                    ResponseBody body = response.body();
                    final String responseBody;
                    if (body == null) {
                        responseBody = "";
                    } else {
                        responseBody = body.string();
                    }
                    logger.log(Logger.Level.ERROR, "Error uploading snapshot: %s %s", status, responseBody);
                } else {
                    success = true;
                }
            } catch (final IOException e) {
                logger.log(Logger.Level.ERROR, "Error uploading snapshot: %s", e.getMessage());
            }

            if (!success) {
                final int backoff = exponentialBackoff.error();
                logger.log(Logger.Level.DEBUG, "Backing off for %s ms", backoff);
                Thread.sleep(backoff);
            }
        }
    }

    private HttpUrl urlForSnapshot(final Snapshot snapshot) {
        Instant started = snapshot.started;
        Instant finished = started.plus(config.uploadInterval);
        HttpUrl.Builder builder = HttpUrl.parse(config.serverAddress)
            .newBuilder()
            .addPathSegment("ingest")
            .addQueryParameter("name", nameWithStaticLabels())
            .addQueryParameter("units", snapshot.eventType.units.id)
            .addQueryParameter("aggregationType", snapshot.eventType.aggregationType.id)
            .addQueryParameter("sampleRate", Long.toString(config.profilingIntervalInHertz()))
            .addQueryParameter("from", Long.toString(started.getEpochSecond()))
            .addQueryParameter("until", Long.toString(finished.getEpochSecond()))
            .addQueryParameter("spyName", Config.DEFAULT_SPY_NAME);
        if (config.format == Format.JFR)
            builder.addQueryParameter("format", "jfr");
        return builder.build();
    }

    private String nameWithStaticLabels() {
        return config.timeseries.newBuilder()
            .addLabels(config.labels)
            .addLabels(Pyroscope.getStaticLabels())
            .build()
            .toString();
    }

}
