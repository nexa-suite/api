package com.nexa.api.tenantaccessgovernance.iam.domain.model.password;

/** Stable IAM policy name used by authentication and invitation flows. */
public final class PasswordPolicy {
	public static final int MINIMUM_LENGTH = com.nexa.api.tenantaccessgovernance.iam.domain.model.password.PasswordPolicyRules.MINIMUM_LENGTH;
	public static final int MAXIMUM_LENGTH = com.nexa.api.tenantaccessgovernance.iam.domain.model.password.PasswordPolicyRules.MAXIMUM_LENGTH;

	private PasswordPolicy() { }

	public static boolean isValid(String password) {
		return com.nexa.api.tenantaccessgovernance.iam.domain.model.password.PasswordPolicyRules.isValid(password);
	}

	public static boolean isValid(String password, int minimumLength) {
		return com.nexa.api.tenantaccessgovernance.iam.domain.model.password.PasswordPolicyRules.isValid(password, minimumLength);
	}
}
