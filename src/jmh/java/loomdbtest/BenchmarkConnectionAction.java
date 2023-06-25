package loomdbtest;

import java.sql.Connection;
import org.openjdk.jmh.infra.Blackhole;

public interface BenchmarkConnectionAction {
    void run(Connection connection, Blackhole blackhole) throws Exception;
}
