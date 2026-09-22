package com.relyon.economizaai.model.enums;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public enum UnidadeFederativa {
    RO(11), AC(12), AM(13), RR(14), PA(15), AP(16), TO(17),
    MA(21), PI(22), CE(23), RN(24), PB(25), PE(26), AL(27), SE(28), BA(29),
    MG(31), ES(32), RJ(33), SP(35),
    PR(41), SC(42), RS(43),
    MS(50), MT(51), GO(52), DF(53);

    private static final Map<Integer, UnidadeFederativa> BY_CODE =
            Arrays.stream(values()).collect(Collectors.toMap(UnidadeFederativa::getIbgeCode, Function.identity()));

    private final int ibgeCode;

    UnidadeFederativa(int ibgeCode) {
        this.ibgeCode = ibgeCode;
    }

    public int getIbgeCode() {
        return ibgeCode;
    }

    public static UnidadeFederativa fromIbgeCode(int code) {
        var uf = BY_CODE.get(code);
        if (uf == null) {
            throw new IllegalArgumentException("Unknown UF IBGE code: " + code);
        }
        return uf;
    }
}
