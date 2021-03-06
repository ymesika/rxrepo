package com.slimgears.rxrepo.sql;

import com.slimgears.rxrepo.expressions.ConstantExpression;
import com.slimgears.rxrepo.expressions.ObjectExpression;
import com.slimgears.rxrepo.expressions.PropertyExpression;
import com.slimgears.util.autovalue.annotations.PropertyMeta;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

public interface SqlExpressionGenerator {
    <S, T> String toSqlExpression(ObjectExpression<S, T> expression);
    <S, T> String toSqlExpression(ObjectExpression<S, T> expression, ObjectExpression<?, S> arg);
    <T> T withParams(List<Object> params, Callable<T> callable);

    default String fromStatement(SqlStatement statement) {
        Arrays.asList(statement.args()).forEach(this::fromConstant);
        return "(" + statement.statement() + ")";
    }

    default String fromProperty(PropertyMeta<?, ?> propertyMeta) {
        return toSqlExpression(PropertyExpression.ofObject(propertyMeta));
    }

    default String fromConstant(Object value) {
        return toSqlExpression(ConstantExpression.of(value));
    }
}
