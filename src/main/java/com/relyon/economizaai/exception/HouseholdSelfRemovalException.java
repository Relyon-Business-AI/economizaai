package com.relyon.economizaai.exception;

/** The host tried to remove themselves — leaving has its own flow ("Sair da casa"). */
public class HouseholdSelfRemovalException extends DomainException {

    public HouseholdSelfRemovalException() {
        super("household.remove.self");
    }
}
