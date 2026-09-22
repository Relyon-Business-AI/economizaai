package com.relyon.economizaai.dto.response;

import java.util.UUID;

/**
 * A category available to a household: either a global enum ({@code id=null,
 * custom=false}) or a household-defined custom category ({@code custom=true}).
 */
public record CategoryResponse(UUID id, String name, boolean custom) {}
