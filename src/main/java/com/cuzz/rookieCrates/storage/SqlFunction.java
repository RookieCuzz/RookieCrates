package com.cuzz.rookieCrates.storage;

import java.sql.SQLException;

@FunctionalInterface
public interface SqlFunction<T> {
    T apply(RookieCratesDao dao) throws SQLException;
}
