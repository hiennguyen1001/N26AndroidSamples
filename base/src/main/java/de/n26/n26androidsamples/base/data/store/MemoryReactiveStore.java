package de.n26.n26androidsamples.base.data.store;

import android.support.annotation.NonNull;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.reactivex.Flowable;
import io.reactivex.processors.FlowableProcessor;
import io.reactivex.processors.PublishProcessor;
import io.reactivex.schedulers.Schedulers;
import polanski.option.Option;
import polanski.option.function.Func1;

import static polanski.option.Option.none;
import static polanski.option.Option.ofObj;

/**
 This reactive store has only a memory cache as form of storage.
 */
public class MemoryReactiveStore<Key, Value> implements ReactiveStore<Key, Value> {

    @NonNull
    private final Store.MemoryStore<Key, Value> cache;

    @NonNull
    private final Func1<Value, Key> extractKeyFromModel;

    @NonNull
    private final FlowableProcessor<Option<List<Value>>> allProcessor;

    @NonNull
    private final Map<Key, FlowableProcessor<Option<Value>>> processorMap = new HashMap<>();

    public MemoryReactiveStore(@NonNull final Func1<Value, Key> extractKeyFromModel,
                               @NonNull final Store.MemoryStore<Key, Value> cache) {
        this.allProcessor = PublishProcessor.<Option<List<Value>>>create().toSerialized();
        this.cache = cache;
        this.extractKeyFromModel = extractKeyFromModel;
    }

    public void storeSingular(@NonNull final Value model) {
        final Key key = extractKeyFromModel.call(model);
        cache.putSingular(model);
        getOrCreateSubjectForKey(key).onNext(ofObj(model));
        // One item has been added/updated, notify to all as well
        final Option<List<Value>> allValues = cache.getAll().map(Option::ofObj).blockingGet(none());
        allProcessor.onNext(allValues);
    }

    public void storeAll(@NonNull final List<Value> modelList) {
        cache.putAll(modelList);
        allProcessor.onNext(ofObj(modelList));
        // Publish in all the existing single item streams.
        // This could be improved publishing only in the items that changed. Maybe use DiffUtils?
        publishInEachKey();
    }

    public void replaceAll(@NonNull final List<Value> modelList) {
        cache.clear();
        storeAll(modelList);
    }

    @NonNull
    public Flowable<Option<Value>> getSingular(@NonNull final Key key) {
        final Option<Value> model = cache.getSingular(key).map(Option::ofObj).blockingGet(none());
        return getOrCreateSubjectForKey(key).startWith(model)
                                            .observeOn(Schedulers.computation());
    }

    @NonNull
    public Flowable<Option<List<Value>>> getAll() {
        final Option<List<Value>> allValues = cache.getAll().map(Option::ofObj).blockingGet(none());
        return allProcessor.startWith(allValues)
                           .observeOn(Schedulers.computation());
    }

    @NonNull
    private FlowableProcessor<Option<Value>> getOrCreateSubjectForKey(@NonNull final Key key) {
        synchronized (processorMap) {
            return ofObj(processorMap.get(key)).orDefault(() -> createAndStoreNewSubjectForKey(key));
        }
    }

    @NonNull
    private FlowableProcessor<Option<Value>> createAndStoreNewSubjectForKey(@NonNull final Key key) {
        final FlowableProcessor<Option<Value>> processor = PublishProcessor.<Option<Value>>create().toSerialized();
        synchronized (processorMap) {
            processorMap.put(key, processor);
        }
        return processor;
    }

    /**
     Publishes the cached data in each independent stream only if it exists already.
     */
    private void publishInEachKey() {
        final Set<Key> keySet;
        synchronized (processorMap) {
            keySet = new HashSet<>(processorMap.keySet());
        }
        for (Key key : keySet) {
            final Option<Value> value = cache.getSingular(key).map(Option::ofObj).blockingGet(none());
            publishInKey(key, value);
        }
    }

    /**
     Publishes the cached value if there is an already existing stream for the passed key. The case where there isn't a stream for the passed key
     means that the data for this key is not being consumed and therefore there is no need to publish.
     */
    private void publishInKey(@NonNull final Key key, @NonNull final Option<Value> model) {
        final FlowableProcessor<Option<Value>> processor;
        synchronized (processorMap) {
            processor = processorMap.get(key);
        }
        ofObj(processor).ifSome(it -> it.onNext(model));
    }
}
