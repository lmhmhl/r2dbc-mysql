/*
 * Copyright 2018-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.mirromutth.r2dbc.mysql;

import com.zaxxer.hikari.HikariDataSource;
import io.r2dbc.spi.Blob;
import io.r2dbc.spi.Clob;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Statement;
import io.r2dbc.spi.test.Example;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.stream.IntStream;

/**
 * Base class considers implementations of {@link Example}.
 */
abstract class MySqlExampleSupport implements Example<String> {

    private final MySqlConnectionFactory connectionFactory;

    private final JdbcOperations jdbcOperations;

    MySqlExampleSupport(MySqlConnectionConfiguration configuration) {
        this.connectionFactory = MySqlConnectionFactory.from(configuration);
        this.jdbcOperations = getJdbc(configuration);
    }

    @Override
    public ConnectionFactory getConnectionFactory() {
        return connectionFactory;
    }

    @Override
    public JdbcOperations getJdbcOperations() {
        return jdbcOperations;
    }

    @Override
    public String getCreateTableWithAutogeneratedKey() {
        return "CREATE TABLE test (id INT PRIMARY KEY AUTO_INCREMENT, value INT)";
    }

    @Override
    public String getIdentifier(int index) {
        return "v" + index;
    }

    @Override
    public String getPlaceholder(int index) {
        return "?v" + index;
    }

    @Override
    public String clobType() {
        return "TEXT";
    }

    @Test
    @Override
    public void returnGeneratedValues() {
        getJdbcOperations().execute(String.format("DROP TABLE `%s`", "test"));
        getJdbcOperations().execute(getCreateTableWithAutogeneratedKey());

        Mono.from(getConnectionFactory().create())
            .flatMapMany(connection -> {
                // Cannot be "INSERT INTO test VALUES(100)" in MySQL, need a function getInsertWithAutogeneratedKey
                Statement statement = connection.createStatement(getInsertWithAutogeneratedKey());

                statement.returnGeneratedValues();

                return Flux.from(statement.execute())
                    .flatMap(it -> it.map((row, rowMetadata) -> row.get(0)))
                    .concatWith(Example.close(connection)); // Should close connection after result consume
            })
            .as(StepVerifier::create)
            .expectNextCount(1)
            .verifyComplete();
    }

    @Test
    @Override
    public void prepareStatement() {
        Mono.from(getConnectionFactory().create())
            .flatMapMany(connection -> {
                Statement statement = connection.createStatement(String.format("INSERT INTO test VALUES(%s)", getPlaceholder(0)));

                IntStream.range(0, 10)
                    .forEach(i -> statement.bind(getIdentifier(0), i).add());

                return Flux.from(statement.execute())
                    .flatMap(Result::getRowsUpdated) // Result should be subscribed
                    .concatWith(Example.close(connection));
            })
            .as(StepVerifier::create)
            .expectNextCount(10).as("values from insertions")
            .verifyComplete();
    }

    @Test
    @Override
    public void blobInsert() {
        Mono.from(getConnectionFactory().create())
            .flatMapMany(connection -> Flux.from(connection

                .createStatement(String.format("INSERT INTO blob_test VALUES (%s)", getPlaceholder(0)))
                .bind(getIdentifier(0), Blob.from(Mono.just(StandardCharsets.UTF_8.encode("test-value")))) // Should be getIdentifier rather than getPlaceholder
                .execute())
                .flatMap(Result::getRowsUpdated) // Result should be subscribed

                .concatWith(Example.close(connection)))
            .as(StepVerifier::create)
            .expectNextCount(1).as("rows inserted")
            .verifyComplete();
    }

    @Test
    @Override
    public void clobInsert() {
        Mono.from(getConnectionFactory().create())
            .flatMapMany(connection -> Flux.from(connection

                .createStatement(String.format("INSERT INTO clob_test VALUES (%s)", getPlaceholder(0)))
                .bind(getIdentifier(0), Clob.from(Mono.just("test-value"))) // Should be getIdentifier rather than getPlaceholder
                .execute())
                .flatMap(Result::getRowsUpdated) // Result should be subscribed

                .concatWith(Example.close(connection)))
            .as(StepVerifier::create)
            .expectNextCount(1).as("rows inserted")
            .verifyComplete();
    }

    @Test
    @Override
    public void bindNull() {
        Mono.from(getConnectionFactory().create())
            .flatMapMany(connection -> Flux.from(connection

                .createStatement(String.format("INSERT INTO test VALUES(%s)", getPlaceholder(0)))
                .bindNull(getIdentifier(0), Integer.class).add()
                .execute())
                .flatMap(Result::getRowsUpdated) // Result should be subscribed

                .concatWith(Example.close(connection)))
            .as(StepVerifier::create)
            .expectNextCount(1).as("rows inserted")
            .verifyComplete();
    }

    private String getInsertWithAutogeneratedKey() {
        return "INSERT INTO test VALUES (DEFAULT,100)";
    }

    private static JdbcOperations getJdbc(MySqlConnectionConfiguration configuration) {
        HikariDataSource dataSource = new HikariDataSource();

        dataSource.setJdbcUrl(String.format("jdbc:mysql://%s:%d/r2dbc", configuration.getHost(), configuration.getPort()));
        dataSource.setUsername(configuration.getUsername());
        dataSource.setPassword(Optional.ofNullable(configuration.getPassword()).map(Object::toString).orElse(null));
        dataSource.setMaximumPoolSize(1);

        return new JdbcTemplate(dataSource);
    }
}
