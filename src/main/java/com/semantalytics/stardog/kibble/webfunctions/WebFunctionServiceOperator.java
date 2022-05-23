package com.semantalytics.stardog.kibble.webfunctions;

import com.complexible.common.base.Pair;
import com.complexible.common.rdf.model.ArrayLiteral;
import com.complexible.stardog.StardogException;
import com.complexible.stardog.plan.PlanNode;
import com.complexible.stardog.plan.QueryTerm;
import com.complexible.stardog.plan.SortType;
import com.complexible.stardog.plan.eval.ExecutionContext;
import com.complexible.stardog.plan.eval.operator.*;
import com.complexible.stardog.plan.eval.operator.impl.AbstractOperator;
import com.complexible.stardog.plan.eval.operator.impl.Solutions;
import com.complexible.stardog.plan.filter.expr.ValueOrError;
import com.google.common.base.Preconditions;
import com.google.common.collect.Iterators;
import com.google.common.collect.Sets;
import com.stardog.stark.Literal;
import com.stardog.stark.Value;
import com.stardog.stark.query.BindingSet;
import com.stardog.stark.query.SelectQueryResult;
import io.github.kawamuray.wasmtime.Func;
import io.github.kawamuray.wasmtime.Instance;
import io.github.kawamuray.wasmtime.Val;
import org.apache.jena.rdfconnection.SparqlQueryConnection;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.stardog.stark.Values.iri;

/**
 * Executable operator for the repeat function
 *
 * @author Michael Grove
 */
public final class WebFunctionServiceOperator extends AbstractOperator implements PropertyFunctionOperator {

    private Value wasmIRI ;

    /**
     * The current solution
     */
    private Solution solution;

    /**
     * The child argument
     */
    private final Optional<Operator> mArg;

    /**
     * An iterator over the child solutions of this operator
     */
    private Iterator<Solution> mInputs = null;

    private SelectQueryResult selectQueryResult;

    private final List<QueryTerm> args;
    private final List<QueryTerm> results;
    private final StardogWasmInstance stardogWasmInstance;

    public WebFunctionServiceOperator(final ExecutionContext theExecutionContext,
                                      final Value wasmIRI,
                                      final List<QueryTerm> args,
                                      final List<QueryTerm> results,
                                      final Operator theOperator,
                                      final StardogWasmInstance stardogWasmInstance) {
        super(theExecutionContext, SortType.UNSORTED);

        mArg = Optional.ofNullable(theOperator);
        this.wasmIRI = wasmIRI;
        this.args = args;
        this.results = results;
        this.stardogWasmInstance = stardogWasmInstance;
        this.stardogWasmInstance.setMappingDictionary(theExecutionContext.getMappings());
    }

    @Override
    protected Solution computeNext() {
        if (mInputs == null) {
            // first call to compute results, perform some init
            // either use our child's solutions, or if we don't have a child, create a single solution to use
            if (mArg.filter(theOp -> !(theOp instanceof EmptyOperator)).isPresent()) {
                // these are the variables the child arg will bind
                Set<Integer> aVars = Sets.newHashSet(mArg.get().getVars());

                // and these are the ones that the pf will bind
                aVars.addAll(results.stream().map(QueryTerm::getName).collect(Collectors.toList()));

                // now we create a solution that contains room for bindings for these variables
                final Solution aSoln = mExecutionContext.getSolutionFactory()
                        .variables(aVars)
                        .newSolution();

                // and transform the child solutions to this one large enough to accomodate our vars
                mInputs = Iterators.transform(mArg.get(), theSoln -> {
                    Solutions.copy(aSoln, theSoln);
                    return aSoln;
                });
            } else if (args.stream().allMatch(QueryTerm::isVariable)) {
                return endOfData();
            } else {
                final Set<Integer> aVars = Sets.newHashSet();

                aVars.addAll(results.stream().filter(QueryTerm::isVariable).map(QueryTerm::getName).collect(Collectors.toList()));

                // we only want to create solutions with the minimum number of variables
                mInputs = Iterators.singletonIterator(mExecutionContext.getSolutionFactory()
                        .variables(aVars)
                        .newSolution());
            }
        }

        while (mInputs.hasNext() || solution != null) {
            if (solution == null && mInputs.hasNext()) {
                solution = mInputs.next();
            }
            if (selectQueryResult == null) {
                try {
                    ValueOrError[] valueOrErrors = args.stream().map(queryTerm -> {
                        if (queryTerm.isVariable()) {
                            return solution.getValue(queryTerm.getName(), getMappings());
                        } else {
                            return queryTerm.getValue();
                        }
                    }).toArray(ValueOrError[]::new);

                    if (Arrays.stream(valueOrErrors).noneMatch(ValueOrError::isError)) {
                        selectQueryResult = stardogWasmInstance.evaluate(Arrays.stream(valueOrErrors).map(ValueOrError::value).toArray(Value[]::new));
                    } else {
                        stardogWasmInstance.close();
                        return endOfData();
                    }
                } catch (IOException e) {
                    stardogWasmInstance.close();
                    return endOfData();
                }
            }
            if(selectQueryResult.hasNext()) {
                final BindingSet bindingSet = selectQueryResult.next();

                IntStream.range(0, results.size()).forEach(i -> {
                    final Value value;
                    if (bindingSet.get(String.format("value_%d", i)) instanceof Literal && ((Literal) bindingSet.get(String.format("value_%d", i))).datatypeIRI().equals(iri("tag:stardog:api:array"))) {
                        value = ArrayLiteral.coerce((Literal) bindingSet.get(String.format("value_%d", i)));
                    } else {
                        value = bindingSet.get(String.format("value_%d", i));
                    }
                    solution.setValue(results.get(i).getName(), value, getMappings());
                });
                return solution;
            } else {
                selectQueryResult.close();
                selectQueryResult = null;
                solution = null;
            }
        }
            stardogWasmInstance.close();
            return endOfData();
    }
            /*

            private long getValue() {
                return mNode.getInput().isConstant()
                        ? mNode.getInput().getIndex()
                        : mValue.get(mNode.getInput().getName());
            }

             */

    /**
     * {@inheritDoc}
     */
    @Override
    protected void performReset() {
        mArg.ifPresent(Operator::reset);
        stardogWasmInstance.close();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<Integer> getVars() {
        return results.stream()
                .filter(QueryTerm::isVariable)
                .map(QueryTerm::getName)
                .collect(Collectors.toSet());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void accept(final OperatorVisitor theOperatorVisitor) {
        theOperatorVisitor.visit(this);
    }
}
