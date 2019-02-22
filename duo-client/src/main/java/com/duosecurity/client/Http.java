package com.duosecurity.client;

import com.squareup.okhttp.Headers;
import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.RequestBody;
import com.squareup.okhttp.Response;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.*;

import java.util.concurrent.TimeUnit;

import org.json.JSONObject;

public class Http {
    public final static int MAX_REQUEST_ATTEMPTS = 8;
    public final static int BACKOFF_FACTOR = 2;
    public final static int MAX_BACKOFF_MS = 32000;

    private String method;
    private String host;
    private String uri;
    private Headers.Builder headers;
    Map<String, String> params = new HashMap<String, String>();
    private Proxy proxy;
    private int timeout = 60;
    private Random random = new Random();
    private OkHttpClient httpClient;

    public static SimpleDateFormat RFC_2822_DATE_FORMAT
        = new SimpleDateFormat("EEE', 'dd' 'MMM' 'yyyy' 'HH:mm:ss' 'Z",
                               Locale.US);

    public static MediaType FORM_ENCODED = MediaType.parse("application/x-www-form-urlencoded");

    public Http(String in_method, String in_host, String in_uri) {
        method = in_method.toUpperCase();
        host = in_host;
        uri = in_uri;

        headers = new Headers.Builder();
        headers.add("Host", host);

        httpClient = new OkHttpClient();
        proxy = null;
        if (proxy != null) {
            httpClient.setProxy(proxy);
        }
        httpClient.setConnectTimeout(timeout, TimeUnit.SECONDS);
        httpClient.setWriteTimeout(timeout, TimeUnit.SECONDS);
        httpClient.setReadTimeout(timeout, TimeUnit.SECONDS);
    }

    public Http(String in_method, String in_host, String in_uri, int timeout) {
        this(in_method, in_host, in_uri);
        this.timeout = timeout;
    }

    public Object executeRequest() throws Exception {
        JSONObject result = (JSONObject)executeJSONRequest();
        return result.get("response");
    }

    public Object executeJSONRequest() throws Exception {
        JSONObject result = new JSONObject(executeRequestRaw());
        if (! result.getString("stat").equals("OK")) {
            throw new Exception("Duo error code ("
                                + result.getInt("code")
                                + "): "
                                + result.getString("message"));
        }
        return result;
    }

    public String executeRequestRaw() throws Exception {
        Response response = executeHttpRequest();
        return response.body().string();
    }

    public Response executeHttpRequest() throws Exception {
      String url = "https://" + host + uri;
      String queryString = createQueryString();

      Request.Builder builder = new Request.Builder();
      if (method.equals("POST")) {
        builder.post(RequestBody.create(FORM_ENCODED, queryString));
      } else if (method.equals("PUT")) {
        builder.put(RequestBody.create(FORM_ENCODED, queryString));
      } else if (method.equals("GET")) {
        if (queryString.length() > 0) {
          url += "?" + queryString;
        }
        builder.get();
      } else if(method.equals("DELETE")) {
        if (queryString.length() > 0) {
          url += "?" + queryString;
        }
        builder.delete();
      } else {
        throw new UnsupportedOperationException("Unsupported method: "
            + method);
      }

      // finish and execute request
      Request request = builder.headers(headers.build()).url(url).build();
      return executeRequest(request);
    }

    private Response executeRequest(Request request) throws Exception {
      Response response = httpClient.newCall(request).execute();
      int attempts = 1;
      while (attempts < MAX_REQUEST_ATTEMPTS && response.code() == 429) {
        sleep(Math.min(
          (int) (Math.pow(BACKOFF_FACTOR, (attempts - 1)) * 1000) + random.nextInt(1000),
          MAX_BACKOFF_MS
        ));
        response = httpClient.newCall(request).execute();
        attempts++;
      }

      return response;
    }

    protected void sleep(long ms) throws Exception {
        Thread.sleep(ms);
    }

    public void signRequest(String ikey, String skey)
      throws UnsupportedEncodingException {
        signRequest(ikey, skey, 2);
    }

    public void signRequest(String ikey, String skey, int sig_version)
      throws UnsupportedEncodingException {
        String date = formatDate(new Date());
        String canon = canonRequest(date, sig_version);
        String sig = signHMAC(skey, canon);

        String auth = ikey + ":" + sig;
        String header = "Basic " + Base64.encodeBytes(auth.getBytes());
        addHeader("Authorization", header);
        if (sig_version == 2) {
            addHeader("Date", date);
        }
    }

    protected String signHMAC(String skey, String msg) {
        try {
            byte[] sig_bytes = Util.hmacSha1(skey.getBytes(), msg.getBytes());
            String sig = Util.bytes_to_hex(sig_bytes);
            return sig;
        } catch (Exception e) {
            return "";
        }
    }

    private String formatDate(Date date) {
        // Could use ThreadLocal or a pool of format objects instead
        // depending on the needs of the application.
        synchronized (RFC_2822_DATE_FORMAT) {
            return RFC_2822_DATE_FORMAT.format(date);
        }
    }

    public void addHeader(String name, String value) {
        headers.add(name, value);
    }

    public void addParam(String name, String value) {
      params.put(name, value);
    }

    public void setProxy(String host, int port) {
        proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port));
    }

    protected String canonRequest(String date, int sig_version)
      throws UnsupportedEncodingException {
        String canon = "";
        if (sig_version == 2) {
            canon += date + "\n";
        }
        canon += method.toUpperCase() + "\n";
        canon += host.toLowerCase() + "\n";
        canon += uri + "\n";
        canon += createQueryString();

        return canon;
    }

    private String createQueryString()
        throws UnsupportedEncodingException {
      ArrayList<String> args = new ArrayList<String>();
      ArrayList<String> keys = new ArrayList<String>();

      for (String key : params.keySet()) {
        keys.add(key);
      }

      Collections.sort(keys);

      for (String key : keys) {
        String name = URLEncoder
            .encode(key, "UTF-8")
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~");
        String value = URLEncoder
            .encode(params.get(key), "UTF-8")
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~");
        args.add(name + "=" + value);
      }

      return Util.join(args.toArray(), "&");
    }
}
