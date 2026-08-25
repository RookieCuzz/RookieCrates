package com.cuzz.rookieCrates.storage;

import java.sql.SQLException;

@FunctionalInterface
public interface SqlConsumer {
    void accept(RookieCratesDao dao) throws SQLException;
}
