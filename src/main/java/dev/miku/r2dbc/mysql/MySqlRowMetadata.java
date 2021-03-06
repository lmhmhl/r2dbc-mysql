/*
 * Copyright 2018-2020 the original author or authors.
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

package dev.miku.r2dbc.mysql;

import dev.miku.r2dbc.mysql.message.server.DefinitionMetadataMessage;
import dev.miku.r2dbc.mysql.util.InternalArrays;
import io.r2dbc.spi.RowMetadata;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

import static dev.miku.r2dbc.mysql.util.AssertUtils.requireNonNull;

/**
 * An implementation of {@link RowMetadata} for MySQL database text/binary results.
 *
 * @see MySqlNames column name searching rules.
 */
final class MySqlRowMetadata implements RowMetadata {

    private static final Comparator<MySqlColumnMetadata> NAME_COMPARATOR = (left, right) ->
        MySqlNames.compare(left.getName(), right.getName());

    private final MySqlColumnMetadata[] originMetadata;

    private final MySqlColumnMetadata[] sortedMetadata;

    /**
     * Copied column names from {@link #sortedMetadata}.
     */
    private final String[] sortedNames;

    private final ColumnNameSet nameSet;

    private MySqlRowMetadata(MySqlColumnMetadata[] metadata) {
        int size = metadata.length;

        switch (size) {
            case 0:
                throw new IllegalArgumentException("Least 1 column metadata");
            case 1:
                String name = metadata[0].getName();

                this.originMetadata = metadata;
                this.sortedMetadata = metadata;
                this.sortedNames = new String[]{name};
                this.nameSet = ColumnNameSet.of(name);

                break;
            default:
                MySqlColumnMetadata[] sortedMetadata = new MySqlColumnMetadata[size];
                System.arraycopy(metadata, 0, sortedMetadata, 0, size);
                Arrays.sort(sortedMetadata, NAME_COMPARATOR);

                String[] originNames = getNames(metadata);
                String[] sortedNames = getNames(sortedMetadata);

                this.originMetadata = metadata;
                this.sortedMetadata = sortedMetadata;
                this.sortedNames = sortedNames;
                this.nameSet = ColumnNameSet.of(originNames, sortedNames);

                break;
        }
    }

    @Override
    public MySqlColumnMetadata getColumnMetadata(int index) {
        if (index < 0 || index >= originMetadata.length) {
            throw new ArrayIndexOutOfBoundsException("Column index " + index + " (total " + originMetadata.length + ')');
        }

        return originMetadata[index];
    }

    @Override
    public MySqlColumnMetadata getColumnMetadata(String name) {
        requireNonNull(name, "name must not be null");

        int index = MySqlNames.nameSearch(this.sortedNames, name);

        if (index < 0) {
            throw new NoSuchElementException("Column name '" + name + "' does not exist");
        }

        return sortedMetadata[index];
    }

    @Override
    public List<MySqlColumnMetadata> getColumnMetadatas() {
        return InternalArrays.asImmutableList(originMetadata);
    }

    @Override
    public Set<String> getColumnNames() {
        return nameSet;
    }

    @Override
    public String toString() {
        return String.format("MySqlRowMetadata{metadata=%s, sortedNames=%s}", Arrays.toString(originMetadata), Arrays.toString(sortedNames));
    }

    MySqlColumnMetadata[] unwrap() {
        return originMetadata;
    }

    static MySqlRowMetadata create(DefinitionMetadataMessage[] columns) {
        int size = columns.length;
        MySqlColumnMetadata[] metadata = new MySqlColumnMetadata[size];

        for (int i = 0; i < size; ++i) {
            metadata[i] = MySqlColumnMetadata.create(i, columns[i]);
        }

        return new MySqlRowMetadata(metadata);
    }

    private static String[] getNames(MySqlColumnMetadata[] metadata) {
        int size = metadata.length;
        String[] names = new String[size];

        for (int i = 0; i < size; ++i) {
            names[i] = metadata[i].getName();
        }

        return names;
    }
}
