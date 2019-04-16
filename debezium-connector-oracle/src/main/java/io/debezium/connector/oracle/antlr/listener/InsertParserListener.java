/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.oracle.antlr.listener;

import io.debezium.connector.oracle.ColumnValue;
import io.debezium.connector.oracle.ColumnValueHolder;
import io.debezium.connector.oracle.DefaultRowLCR;
import io.debezium.connector.oracle.RowLCR;
import io.debezium.connector.oracle.antlr.OracleDmlParser;
import io.debezium.data.Envelope;
import io.debezium.ddl.parser.oracle.generated.PlSqlParser;
import io.debezium.relational.Column;
import io.debezium.text.ParsingException;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static io.debezium.antlr.AntlrDdlParser.getText;

/**
 * This class parses Oracle INSERT statements.
 * if the original tested query was:  insert into DEBEZIUM (id,col3) values (2, 'some text')
 *
 * LogMiner will supply:
 *
 * insert into "DEBEZIUM"("ID","COL1","COL2","COL3","COL4","COL5","COL6","COL7","COL8","COL9","COL10")
 *                      values (2,NULL,'debezium','some text',NULL,NULL,NULL,NULL,NULL,EMPTY_BLOB(),EMPTY_CLOB())
 * update "DEBEZIUM" set "COL9" = NULL, "COL10" = NULL where "ID" = 2 and "COL1" IS NULL and "COL2" = 'debezium'
 *                      and "COL3" = 'some text' and "COL4" IS NULL and "COL5" IS NULL and "COL6" IS NULL
 *                      and "COL7" IS NULL and "COL8" IS NULL
 *
 */
public class InsertParserListener extends BaseDmlParserListener<Integer> {

    InsertParserListener(String catalogName, String schemaName, OracleDmlParser parser) {
        super(catalogName, schemaName, parser);
    }

    @Override
    protected Integer getKey(Column column, int index) {
        return index;
    }

    @Override
    public void enterInsert_statement(PlSqlParser.Insert_statementContext ctx) {
        init(ctx.single_table_insert().insert_into_clause().general_table_ref().dml_table_expression_clause());
        oldColumnValues.clear();
        super.enterInsert_statement(ctx);
    }

    @Override
    public void enterValues_clause(PlSqlParser.Values_clauseContext ctx) {
        if (table == null) {
            throw new ParsingException(null, "Trying to parse a statement for a table which does not exist. " +
                    "Statement: " + getText(ctx));
        }

        List<PlSqlParser.ExpressionContext> values = ctx.expressions().expression();
        for (int i = 0; i < values.size(); i++) {
            PlSqlParser.ExpressionContext value = values.get(i);
            ColumnValueHolder columnObject = newColumnValues.get(i);

            String columnName = columnObject.getColumnValue().getColumnName();
            Column column = table.columnWithName(columnName);

            String valueText = value.logical_expression().getText();
            valueText = removeApostrophes(valueText);
            Object valueObject = convertValueToSchemaType(column, valueText, converters, preConverter);

            columnObject.getColumnValue().setColumnData(valueObject);
        }
        super.enterValues_clause(ctx);
    }

    @Override
    public void exitSingle_table_insert(PlSqlParser.Single_table_insertContext ctx) {
        List<ColumnValue> actualNewValues = newColumnValues.values()
                .stream().map(ColumnValueHolder::getColumnValue).collect(Collectors.toList());
        RowLCR newRecord = new DefaultRowLCR(Envelope.Operation.CREATE, actualNewValues, Collections.emptyList());
        parser.setRowLCR(newRecord);// todo, what is the way to emit it?
        //parser.signalChangeEvent();
        super.exitSingle_table_insert(ctx);
    }
}
