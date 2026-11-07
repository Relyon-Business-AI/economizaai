package com.relyon.economizaai.exception;

/**
 * O refresh token não pôde renovar a sessão (desconhecido, expirado, revogado, ou reuso fora da
 * janela de tolerância). É uma falha de AUTENTICAÇÃO → 401, pra o app deslogar limpo e pedir login
 * de novo — diferente de {@link InvalidAuthTokenException} (400), usada nos fluxos de verificação de
 * e-mail / reset de senha, onde um código ruim é "bad request", não sessão expirada.
 */
public class RefreshTokenRejectedException extends DomainException {

    public RefreshTokenRejectedException() {
        super("auth.refresh.rejected");
    }
}
