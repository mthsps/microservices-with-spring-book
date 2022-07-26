package com.microservices.composite.product;

import com.microservices.api.composite.product.ProductAggregate;
import com.microservices.api.composite.product.RecommendationSummary;
import com.microservices.api.composite.product.ReviewSummary;
import com.microservices.api.core.product.Product;
import com.microservices.api.core.recommendation.Recommendation;
import com.microservices.api.core.review.Review;
import com.microservices.api.event.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.OutputDestination;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.Message;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.ArrayList;
import java.util.List;

import static com.microservices.api.event.Event.Type.CREATE;
import static com.microservices.api.event.Event.Type.DELETE;
import static com.microservices.composite.product.IsSameEvent.sameEventExceptCreatedAt;
import static java.util.Collections.singletonList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;
import static org.springframework.http.HttpStatus.OK;
import static reactor.core.publisher.Mono.just;

@SpringBootTest(
        webEnvironment = RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
@Import({TestChannelBinderConfiguration.class})
public class MessagingTests {

    private static final Logger LOG = LoggerFactory.getLogger(MessagingTests.class);

    @Autowired
    private WebTestClient client;

    @Autowired
    private OutputDestination target;

    @BeforeEach
    public void setUp() {
        purgeMessages("products");
        purgeMessages("recommendations");
        purgeMessages("reviews");
    }

    @Test
    public void createCompositeProduct1() {

        ProductAggregate composite = new ProductAggregate(1, "name", 1, null, null, null);
        postAndVerifyProduct(composite, OK);

        final List<String> productMessages = getMessages("products");
        final List<String> recommendationMessages = getMessages("recommendations");
        final List<String> reviewMessages = getMessages("reviews");

        assertEquals(1, productMessages.size());

        Event<Integer, Product> expectedEvent =
                new Event(CREATE, composite.getProductId(), new Product(composite.getProductId(), composite.getName(), composite.getWeight(), null));
        assertThat(productMessages.get(0), is(sameEventExceptCreatedAt(expectedEvent)));

        assertEquals(0, recommendationMessages.size());
        assertEquals(0, reviewMessages.size());
    }

    @Test
    public void createCompositeProduct2() {

        ProductAggregate composite = new ProductAggregate(1, "name", 1,
                singletonList(new RecommendationSummary(1, "a", 1, "c")),
                singletonList(new ReviewSummary(1, "a", "s", "c")), null);
        postAndVerifyProduct(composite, OK);

        final List<String> productMessages = getMessages("products");
        final List<String> recommendationMessages = getMessages("recommendations");
        final List<String> reviewMessages = getMessages("reviews");

        assertEquals(1, productMessages.size());

        Event<Integer, Product> expectedProductEvent =
                new Event(CREATE, composite.getProductId(),
                        new Product(composite.getProductId(), composite.getName(), composite.getWeight(), null));
        assertThat(productMessages.get(0), is(sameEventExceptCreatedAt(expectedProductEvent)));

        assertEquals(1, recommendationMessages.size());

        RecommendationSummary rec = composite.getRecommendations().get(0);
        Event<Integer, Product> expectedRecommendationEvent =
                new Event(CREATE, composite.getProductId(),
                        new Recommendation(composite.getProductId(), rec.getRecommendationId(), rec.getAuthor(), rec.getRate(), rec.getContent(), null));
        assertThat(recommendationMessages.get(0), is(sameEventExceptCreatedAt(expectedRecommendationEvent)));

        assertEquals(1, reviewMessages.size());

        ReviewSummary rev = composite.getReviews().get(0);
        Event<Integer, Product> expectedReviewEvent =
                new Event(CREATE, composite.getProductId(), new Review(composite.getProductId(), rev.getReviewId(), rev.getAuthor(), rev.getSubject(), rev.getContent(), null));
        assertThat(reviewMessages.get(0), is(sameEventExceptCreatedAt(expectedReviewEvent)));
    }

    @Test
    public void deleteCompositeProduct() {
        deleteAndVerifyProduct(1, OK);

        final List<String> productMessages = getMessages("products");
        final List<String> recommendationMessages = getMessages("recommendations");
        final List<String> reviewMessages = getMessages("reviews");

        assertEquals(1, productMessages.size());

        Event<Integer, Product> expectedProductEvent = new Event(DELETE, 1, null);
        assertThat(productMessages.get(0), is(sameEventExceptCreatedAt(expectedProductEvent)));

        assertEquals(1, recommendationMessages.size());

        Event<Integer, Product> expectedRecommendationEvent = new Event(DELETE, 1, null);
        assertThat(recommendationMessages.get(0), is(sameEventExceptCreatedAt(expectedRecommendationEvent)));

        assertEquals(1, reviewMessages.size());

        Event<Integer, Product> expectedReviewEvent = new Event(DELETE, 1, null);
        assertThat(reviewMessages.get(0), is(sameEventExceptCreatedAt(expectedReviewEvent)));
    }

    private void purgeMessages(String bindingName) {
        getMessages(bindingName);
    }

    private List<String> getMessages(String bindingName) {
        List<String> messages = new ArrayList<>();
        boolean anyMoreMessages = true;

        while (anyMoreMessages) {
            Message<byte[]> message = getMessage(bindingName);

            if (message == null) {
                anyMoreMessages = false;

            } else {
                messages.add(new String(message.getPayload()));
            }
        }
        return messages;
    }

    private Message<byte[]> getMessage(String bindingName) {
        try {
            return target.receive(0, bindingName);
        } catch (NullPointerException npe) {
            LOG.trace("getMessage() received a NPE with binding = {}", bindingName);
            return null;
        }
    }

    private void postAndVerifyProduct(ProductAggregate compositeProduct, HttpStatus expectedStatus) {
        client.post()
                .uri("/product-composite")
                .body(just(compositeProduct), ProductAggregate.class)
                .exchange()
                .expectStatus().isEqualTo(expectedStatus);
    }

    private void deleteAndVerifyProduct(int productId, HttpStatus expectedStatus) {
        client.delete()
                .uri("/product-composite/" + productId)
                .exchange()
                .expectStatus().isEqualTo(expectedStatus);
    }
}
