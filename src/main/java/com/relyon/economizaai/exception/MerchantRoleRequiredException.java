package com.relyon.economizaai.exception;

public class MerchantRoleRequiredException extends DomainException {

    public MerchantRoleRequiredException() {
        super("merchant.access.role.required");
    }
}
