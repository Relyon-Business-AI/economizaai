package com.relyon.economizaai.exception;

public class InvalidShoppingListItemException extends DomainException {

    public InvalidShoppingListItemException() {
        super("shopping.list.item.required");
    }
}
