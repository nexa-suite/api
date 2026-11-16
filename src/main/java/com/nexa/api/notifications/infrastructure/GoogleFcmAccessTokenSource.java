package com.nexa.api.notifications.infrastructure;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import java.io.IOException;
import java.util.List;

/** Application Default Credentials are loaded externally; no credentials are stored in Nexa. */
public final class GoogleFcmAccessTokenSource implements FcmPushProvider.AccessTokenSource {
    private static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
    private GoogleCredentials credentials;

    @Override public synchronized String accessToken() throws IOException {
        if (credentials == null) credentials = GoogleCredentials.getApplicationDefault().createScoped(List.of(SCOPE));
        credentials.refreshIfExpired();
        AccessToken token = credentials.getAccessToken();
        return token == null ? null : token.getTokenValue();
    }
}
