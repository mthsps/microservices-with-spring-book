package com.microservices.core.review.services;

import com.microservices.api.core.review.Review;
import com.microservices.api.core.review.ReviewService;
import com.microservices.api.exceptions.InvalidInputException;
import com.microservices.core.review.persistence.ReviewEntity;
import com.microservices.core.review.persistence.ReviewRepository;
import com.microservices.util.http.ServiceUtil;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.util.List;
import java.util.function.Supplier;

import static java.util.logging.Level.FINE;

@RestController
public class ReviewServiceImpl implements ReviewService {

    private static final Logger LOG = LoggerFactory.getLogger(ReviewServiceImpl.class);

    private final ReviewRepository repository;

    private final ReviewMapper mapper;

    private final ServiceUtil serviceUtil;

    private final Scheduler scheduler;

    @Autowired
    public ReviewServiceImpl(Scheduler scheduler, ReviewRepository repository, ReviewMapper mapper, ServiceUtil serviceUtil) {
        this.scheduler = scheduler;
        this.repository = repository;
        this.mapper = mapper;
        this.serviceUtil = serviceUtil;
    }

    @Override
    public Mono<Review> createReview(Review body) {

        if (body.getProductId() < 1) {
            throw new InvalidInputException("Invalid productId: " + body.getProductId());
        }

        return asyncMono(() -> Mono.fromCallable(() -> internalCreateReview(body)));
    }

    private Review internalCreateReview(Review body) {
        try {
            ReviewEntity entity = mapper.apiToEntity(body);
            ReviewEntity newEntity = repository.save(entity);

            LOG.debug("createReview: created a review entity: {}/{}", body.getProductId(), body.getReviewId());
            return mapper.entityToApi(newEntity);

        } catch (DataIntegrityViolationException dive) {
            throw new InvalidInputException("Duplicate key, Product Id: " + body.getProductId() + ", Review Id:" + body.getReviewId());
        }
    }

    @Override
    public Flux<Review> getReviews(int productId) {

        if (productId < 1) {
            throw new InvalidInputException("Invalid productId: " + productId);
        }

        LOG.info("Will get reviews for product with id={}", productId);

        return asyncFlux(() -> Flux.fromIterable(internalGetReviews(productId))).log(null, FINE);
    }

    private List<Review> internalGetReviews(int productId) {

        List<ReviewEntity> entityList = repository.findByProductId(productId);
        List<Review> list = mapper.entityListToApiList(entityList);
        list.forEach(e -> e.setServiceAddress(serviceUtil.getServiceAddress()));

        LOG.debug("getReviews: response size: {}", list.size());

        return list;
    }

    @Override
    public Mono<Void> deleteReviews(int productId) {

        if (productId < 1) {
            throw new InvalidInputException("Invalid productId: " + productId);
        }

        return asyncMono(() -> Mono.fromRunnable(() -> internalDeleteReviews(productId)));
    }

    private void internalDeleteReviews(int productId) {

        LOG.debug("deleteReviews: tries to delete reviews for the product with productId: {}", productId);

        repository.deleteAll(repository.findByProductId(productId));
    }

    private <T> Flux<T> asyncFlux(Supplier<Publisher<T>> publisherSupplier) {
        return Flux.defer(publisherSupplier).subscribeOn(scheduler);
    }

    private <T> Mono<T> asyncMono(Supplier<Mono<T>> publisherSupplier) {
        return Mono.defer(publisherSupplier).subscribeOn(scheduler);
    }
}
