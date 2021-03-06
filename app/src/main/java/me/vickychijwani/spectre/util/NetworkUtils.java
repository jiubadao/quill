package me.vickychijwani.spectre.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.StringDef;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;

import io.reactivex.Observable;
import me.vickychijwani.spectre.error.UrlNotFoundException;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import retrofit2.Response;

public class NetworkUtils {

    @Retention(RetentionPolicy.SOURCE)
    @StringDef({SCHEME_HTTP, SCHEME_HTTPS})
    public @interface Scheme {}

    public static final String SCHEME_HTTP = "http://";
    public static final String SCHEME_HTTPS = "https://";

    /**
     * Check whether there is any network with a usable connection.
     */
    public static boolean isConnected(Context context) {
        ConnectivityManager connectivityManager = (ConnectivityManager) context
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
        return activeNetworkInfo != null && activeNetworkInfo.isConnected();
    }

    public static boolean isUnauthorized(@Nullable Response response) {
        if (response == null) {
            return false;
        }
        // Ghost returns 403 Forbidden in some cases, inappropriately
        // see this for what 401 vs 403 should mean: http://stackoverflow.com/a/3297081/504611
        return response.code() == HttpURLConnection.HTTP_UNAUTHORIZED
                || response.code() == HttpURLConnection.HTTP_FORBIDDEN;
    }

    public static boolean isNotModified(@Nullable Response response) {
        //noinspection SimplifiableIfStatement
        if (response == null) {
            return false;
        }
        return response.code() == HttpURLConnection.HTTP_NOT_MODIFIED;
    }

    public static boolean isUnrecoverableError(@Nullable Response response) {
        if (response == null) {
            return false;
        }
        return response.code() >= 400 && isUnauthorized(response);
    }

    public static boolean isConnectionError(Throwable error) {
        return error instanceof ConnectException || error instanceof SocketTimeoutException;
    }

    public static String makeAbsoluteUrl(@NonNull String baseUrl, @NonNull String relativePath) {
        // handling for protocol-relative URLs
        // can't remember which scenario actually produces these URLs except maybe the Markdown preview
        if (relativePath.startsWith("//")) {
            relativePath = "http:" + relativePath;
        }

        // maybe relativePath is already absolute
        if (relativePath.startsWith(SCHEME_HTTP) || relativePath.startsWith(SCHEME_HTTPS)) {
            return relativePath;
        }

        boolean baseHasSlash = baseUrl.endsWith("/");
        boolean relHasSlash = relativePath.startsWith("/");
        if (baseHasSlash && relHasSlash) {
            return baseUrl + relativePath.substring(1);
        } else if ((!baseHasSlash && relHasSlash) || (baseHasSlash && !relHasSlash)) {
            return baseUrl + relativePath;
        } else {
            return baseUrl + "/" + relativePath;
        }
    }

    public static Observable<String> checkGhostBlog(@NonNull String blogUrl,
                                                    @NonNull OkHttpClient client) {
        final String adminPagePath = "/ghost/";
        String adminPageUrl = makeAbsoluteUrl(blogUrl, adminPagePath);
        return checkUrl(adminPageUrl, client)
                .flatMap(response -> {
                    if (response.isSuccessful()) {
                        // the request may have been redirected, most commonly from HTTP => HTTPS
                        // so pick up the eventual URL of the blog and use that
                        // (even if the user manually entered HTTP - it's certainly a mistake)
                        // to get that, chop off the admin page path from the end
                        String potentiallyRedirectedUrl = response.request().url().toString();
                        String finalBlogUrl = potentiallyRedirectedUrl.replaceFirst(adminPagePath + "?$", "");
                        return Observable.just(finalBlogUrl);
                    } else if (response.code() == HttpURLConnection.HTTP_NOT_FOUND) {
                        return Observable.error(new UrlNotFoundException(blogUrl));
                    } else {
                        return Observable.error(new RuntimeException("Response code " + response.code()
                                + " when request admin page"));
                    }
                });
    }

    public static Observable<okhttp3.Response> checkUrl(@NonNull String url,
                                                        @NonNull OkHttpClient client) {
        try {
            Request request = new Request.Builder()
                    .url(url)
                    .head()     // make a HEAD request because we only want the response code
                    .build();
            return networkCall(client.newCall(request));
        } catch (IllegalArgumentException e) {
            // invalid url (whitespace chars etc)
            return Observable.error(new MalformedURLException("Invalid Ghost admin address: " + url));
        }
    }

    public static Observable<okhttp3.Response> networkCall(@NonNull Call call) {
        return Observable.create(emitter -> {
            // cancel the request when there are no subscribers
            emitter.setCancellable(call::cancel);
            try {
                emitter.onNext(call.execute());
                emitter.onComplete();
            } catch (IOException e) {
                emitter.onError(e);
            }
        });
    }

}
