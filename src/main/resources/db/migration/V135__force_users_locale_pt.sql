-- Produto brasileiro (NFC-e/CPF/SEFAZ) e o app NÃO tem seletor de idioma — o locale sempre foi
-- capturado do idioma do APARELHO no cadastro, nunca escolhido. Celular em inglês virava
-- locale='en' e recebia digest/e-mails em inglês (bug percebido pelo dono). A partir de agora o
-- backend força pt (FixedLocaleResolver); aqui normalizamos as contas já existentes marcadas 'en'.
-- Os textos em i18n/messages_en ficam no código pra um futuro seletor de idioma de verdade.
UPDATE users SET locale = 'pt' WHERE locale IS NULL OR locale <> 'pt';
