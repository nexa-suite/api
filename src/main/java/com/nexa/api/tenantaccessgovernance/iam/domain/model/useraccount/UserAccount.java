package com.nexa.api.tenantaccessgovernance.iam.domain.model.useraccount;

/**
 * Identity account aggregate. Access policy, tenant membership and credentials are external concerns.
 */
public final class UserAccount {
	private final UserAccountId id;
	private Username username;
	private EmailAddress email;
	private DisplayName displayName;
	private UserAccountStatus status;

	private UserAccount(UserAccountId id, Username username, EmailAddress email, DisplayName displayName) {
		this.id = required(id, "User account id");
		this.username = required(username, "Username");
		this.email = required(email, "Email address");
		this.displayName = required(displayName, "Display name");
		this.status = UserAccountStatus.ACTIVE;
	}

	public static UserAccount create(UserAccountId id, Username username, EmailAddress email, DisplayName displayName) {
		return new UserAccount(id, username, email, displayName);
	}

	public static UserAccount create(UserAccountId id, EmailAddress email, DisplayName displayName) {
		return create(id, new Username(email.value()), email, displayName);
	}

	public UserAccountId id() { return id; }
	public UserAccountId accountId() { return id; }
	public Username username() { return username; }
	public EmailAddress email() { return email; }
	public DisplayName displayName() { return displayName; }
	public DisplayName fullName() { return displayName; }
	public UserAccountStatus status() { return status; }
	public boolean isActive() { return status == UserAccountStatus.ACTIVE; }
	public boolean canAuthenticate() { return isActive(); }

	public void changeUsername(Username newUsername) { username = required(newUsername, "Username"); }
	public void changeEmail(EmailAddress newEmail) { email = required(newEmail, "Email address"); }
	public void rename(DisplayName newDisplayName) { displayName = required(newDisplayName, "Display name"); }

	public void activate() { status = UserAccountStatus.ACTIVE; }
	public void suspend() { status = UserAccountStatus.SUSPENDED; }
	public void disable() { status = UserAccountStatus.DISABLED; }

	private static <T> T required(T value, String label) {
		if (value == null) throw new UserAccountInvariantViolation(label + " is required");
		return value;
	}
}
