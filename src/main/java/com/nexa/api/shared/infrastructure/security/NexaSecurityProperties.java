package com.nexa.api.shared.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "nexa.security")
public class NexaSecurityProperties {
	private String issuer;
	private String audience;
	private Duration accessTokenTtl = Duration.ofMinutes(15);
	private Duration refreshTokenTtl = Duration.ofDays(30);
	private String rsaPublicKey;
	private String rsaPrivateKey;
	private List<String> allowedOrigins = new ArrayList<>();
	private boolean refreshCookieSecure = true;
	private boolean allowEphemeralKeys;

	public String getIssuer() { return issuer; }
	public void setIssuer(String issuer) { this.issuer = issuer; }
	public String getAudience() { return audience; }
	public void setAudience(String audience) { this.audience = audience; }
	public Duration getAccessTokenTtl() { return accessTokenTtl; }
	public void setAccessTokenTtl(Duration accessTokenTtl) { this.accessTokenTtl = accessTokenTtl; }
	public Duration getRefreshTokenTtl() { return refreshTokenTtl; }
	public void setRefreshTokenTtl(Duration refreshTokenTtl) { this.refreshTokenTtl = refreshTokenTtl; }
	public String getRsaPublicKey() { return rsaPublicKey; }
	public void setRsaPublicKey(String rsaPublicKey) { this.rsaPublicKey = rsaPublicKey; }
	public String getRsaPrivateKey() { return rsaPrivateKey; }
	public void setRsaPrivateKey(String rsaPrivateKey) { this.rsaPrivateKey = rsaPrivateKey; }
	public List<String> getAllowedOrigins() { return allowedOrigins; }
	public void setAllowedOrigins(List<String> allowedOrigins) { this.allowedOrigins = allowedOrigins; }
	public boolean isRefreshCookieSecure() { return refreshCookieSecure; }
	public void setRefreshCookieSecure(boolean refreshCookieSecure) { this.refreshCookieSecure = refreshCookieSecure; }
	public boolean isAllowEphemeralKeys() { return allowEphemeralKeys; }
	public void setAllowEphemeralKeys(boolean allowEphemeralKeys) { this.allowEphemeralKeys = allowEphemeralKeys; }
}
