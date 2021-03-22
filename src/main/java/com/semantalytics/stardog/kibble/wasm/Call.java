package com.semantalytics.stardog.kibble.wasm;

import com.complexible.common.rdf.model.ArrayLiteral;
import com.complexible.stardog.index.dictionary.MappingDictionary;
import com.complexible.stardog.plan.filter.AbstractExpression;
import com.complexible.stardog.plan.filter.Expression;
import com.complexible.stardog.plan.filter.ExpressionVisitor;
import com.complexible.stardog.plan.filter.ValueSolution;
import com.complexible.stardog.plan.filter.expr.ValueOrError;
import com.complexible.stardog.plan.filter.functions.UserDefinedFunction;
import com.google.common.cache.*;
import com.google.common.collect.Lists;
import com.stardog.stark.Value;
import com.stardog.stark.query.Binding;
import com.stardog.stark.query.BindingSet;
import com.stardog.stark.query.BindingSets;
import com.stardog.stark.query.SelectQueryResult;
import com.stardog.stark.query.impl.SelectQueryResultImpl;
import com.stardog.stark.query.io.QueryResultFormats;
import com.stardog.stark.query.io.QueryResultParsers;
import com.stardog.stark.query.io.QueryResultWriters;
import org.apache.commons.io.IOUtils;
import org.wasmer.Instance;
import org.wasmer.Memory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.stream.IntStream;

import static java.util.stream.Collectors.*;

public class Call extends AbstractExpression implements UserDefinedFunction {

    private int memorySize = 1;

    /* NOTES
    function to get docs wasm:doc?
    support ipfs:// functions
    caching wasm?
    how to force a reload?
    function versioning? This would be helped with ipfs
    do I need to handle expanding memory????
     */
    private RemovalListener<URL, Instance> removalListener = new RemovalListener<URL, Instance>() {
        @Override
        public void onRemoval(RemovalNotification<URL, Instance> removal) {
            Instance instance = removal.getValue();
            instance.close();
        }
    };

    private LoadingCache<URL, Instance> instanceCache = CacheBuilder.newBuilder()
                                                                .softValues()
                                                                .removalListener(removalListener)
                                                                .build(new CacheLoader<URL, Instance>() {
        @Override
        public Instance load(URL url) throws IOException {
            return new Instance(getWasm(url));
        }
    });

    public Call() {
        super(new Expression[0]);
    }

    public Call(final Call call) {
        super(call);
    }

    @Override
    public ValueOrError evaluate(final ValueSolution valueSolution) {
        if(getArgs().size() > 0) {
            final ValueOrError[] valueOrErrors = getArgs().stream().map(e -> e.evaluate(valueSolution)).toArray(ValueOrError[]::new);
            if (Arrays.stream(valueOrErrors).noneMatch(ValueOrError::isError)) {
                final Value[] values = Arrays.stream(valueOrErrors).map(ValueOrError::value).toArray(Value[]::new);
                final URL wasmUrl;

                final List<String> vars = Lists.newArrayListWithCapacity(values.length);
                List<BindingSet> bindings = IntStream.range(0, values.length).mapToObj(i -> {
                    vars.add(String.format("value[%d]", i));
                    return BindingSets.builder().add(vars.get(i), values[i]).build();
                }).collect(toList());

                final SelectQueryResult queryResult = new SelectQueryResultImpl(vars, bindings);
                final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                try {
                    QueryResultWriters.write(queryResult, byteArrayOutputStream, QueryResultFormats.JSON);
                } catch (IOException e) {
                    return ValueOrError.Error;
                }

                try {
                    wasmUrl = new URL(values[0].toString());
                } catch (MalformedURLException e) {
                    return ValueOrError.Error;
                }

                final Instance instance;

                try {
                    instance = instanceCache.get(wasmUrl);
                } catch (ExecutionException e) {
                    return ValueOrError.Error;
                }

                Integer input_pointer = (Integer) instance.exports.getFunction("allocate").apply(byteArrayOutputStream.toByteArray().length)[0];

                Memory memory = instance.exports.getMemory("memory");
                ByteBuffer memoryBuffer = memory.buffer();
                byte[] input = byteArrayOutputStream.toByteArray();
                int pages = (int)Math.ceil(input.length / 64.0);
                if(pages > memorySize) {
                    memory.grow(pages - memorySize);
                    memorySize = pages;
                }
                memoryBuffer.position(input_pointer);
                memoryBuffer.put(input);

                final Integer output_pointer = (Integer) instance.exports.getFunction("internalEvaluate").apply(input_pointer)[0];

                final SelectQueryResult selectQueryResult;
                try {
                    selectQueryResult = QueryResultParsers.readSelect(new ByteArrayInputStream(readResult(memory, output_pointer).toByteArray()), QueryResultFormats.JSON);
                } catch (IOException e) {
                    return ValueOrError.Error;
                }

                final Optional<BindingSet> bs = selectQueryResult.stream().findFirst();

                try {
                    if (bs.isPresent()) {
                        if (bs.get().size() > 1) {
                            final MappingDictionary mappingDictionary = valueSolution.getDictionary();
                            final long[] ids = bs.get().stream().map(b -> b.get()).mapToLong(v -> mappingDictionary.add(v)).toArray();
                            return ValueOrError.General.of(new ArrayLiteral(ids));
                        } else if (bs.get().size() == 1) {
                            final Optional<Binding> firstVar = bs.get().stream().findFirst();
                            if (firstVar.isPresent()) {
                                return ValueOrError.General.of(firstVar.get().value());
                            } else {
                                return ValueOrError.Error;
                            }
                        } else {
                            return ValueOrError.Error;
                        }
                    } else {
                        return ValueOrError.Error;
                    }
                } finally {
                    instance.exports.getFunction("deallocate").apply(input_pointer, byteArrayOutputStream.toByteArray().length);
                }
            } else {
                return ValueOrError.Error;
            }
        } else {
            return ValueOrError.Error;
        }
    }

    private byte[] getWasm(final URL wasmUrl) throws IOException {
        final ByteArrayOutputStream baos;

        URLConnection conn = wasmUrl.openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.connect();

        baos = new ByteArrayOutputStream();
        IOUtils.copy(conn.getInputStream(), baos);
        return baos.toByteArray();
    }

    private ByteArrayOutputStream readResult(final Memory memory, final Integer output_pointer) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        StringBuilder output = new StringBuilder();
        ByteBuffer memoryBuffer = memory.buffer();

        for (Integer i = output_pointer, max = memoryBuffer.limit(); i < max; ++i) {
            final byte[] b = new byte[1];
            memoryBuffer.position(i);
            memoryBuffer.get(b);

            if (b[0] == 0) {
                break;
            }
            baos.write(b[0]);

            output.appendCodePoint(b[0]);
        }

        return baos;
    }

    @Override
    public String getName() {
        return WasmVocabulary.call.toString();
    }

    @Override
    public List<String> getNames() {
        return Lists.newArrayList(getName());
    }

    @Override
    public Call copy() {
        return new Call(this);
    }

    @Override
    public void accept(final ExpressionVisitor expressionVisitor) {
        expressionVisitor.visit(this);
    }
}
