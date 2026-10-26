package com.relyon.economizaai.service.chain;

import java.io.IOException;
import java.io.InputStream;

public interface ChainLogoStorage {

    String store(InputStream bytes, String contentType, long sizeBytes) throws IOException;

    byte[] read(String key) throws IOException;

    void delete(String key) throws IOException;
}
