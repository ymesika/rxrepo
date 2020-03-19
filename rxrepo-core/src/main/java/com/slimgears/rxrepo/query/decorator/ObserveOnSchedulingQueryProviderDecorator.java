package com.slimgears.rxrepo.query.decorator;

import com.slimgears.rxrepo.expressions.Aggregator;
import com.slimgears.rxrepo.query.Notification;
import com.slimgears.rxrepo.query.provider.QueryInfo;
import com.slimgears.rxrepo.query.provider.QueryProvider;
import com.slimgears.rxrepo.util.SchedulingProvider;
import io.reactivex.Observable;

public class ObserveOnSchedulingQueryProviderDecorator extends AbstractQueryProviderDecorator {
    private final SchedulingProvider schedulingProvider;

    private ObserveOnSchedulingQueryProviderDecorator(QueryProvider underlyingProvider, SchedulingProvider schedulingProvider) {
        super(underlyingProvider);
        this.schedulingProvider = schedulingProvider;
    }

    public static QueryProvider.Decorator create(SchedulingProvider schedulingProvider) {
        return src -> new ObserveOnSchedulingQueryProviderDecorator(src, schedulingProvider);
    }

    @Override
    public <K, S, T> Observable<Notification<T>> queryAndObserve(QueryInfo<K, S, T> queryInfo, QueryInfo<K, S, T> observeInfo) {
        return super.queryAndObserve(queryInfo, observeInfo)
                .compose(schedulingProvider.applyScope());
    }

    @Override
    public <K, S, T> Observable<Notification<T>> liveQuery(QueryInfo<K, S, T> query) {
        return super.liveQuery(query)
                .compose(schedulingProvider.applyScope());
    }

    @Override
    public <K, S, T, R> Observable<R> liveAggregate(QueryInfo<K, S, T> query, Aggregator<T, T, R> aggregator) {
        return super.liveAggregate(query, aggregator)
                .compose(schedulingProvider.applyScope());
    }
}
