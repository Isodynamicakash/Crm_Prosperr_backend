package com.prosperr.crm.identity.application;

/** Port: verifies a Google ID token from the frontend's "Sign in with Google". */
public interface GoogleTokenVerifier {
    record GoogleIdentity(String subject, String email, boolean emailVerified, String hostedDomain) {}
    GoogleIdentity verify(String idToken);
}
