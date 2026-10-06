package com.relyon.economizaai.exception;

/** Role changes touching ADMIN (demoting an admin or promoting to admin) are refused via API. */
public class AdminRoleChangeException extends DomainException {

    public AdminRoleChangeException() {
        super("admin.user.role_change.forbidden");
    }
}
