package com.laserpay.pdei.statebuilder;

import com.laserpay.pdei.persistence.entity.BaseEntity;
import com.laserpay.pdei.persistence.entity.CommunicationEntity;
import com.laserpay.pdei.persistence.entity.CustomerEntity;
import com.laserpay.pdei.persistence.entity.DeliveryEntity;
import com.laserpay.pdei.persistence.entity.DisputeEntity;
import com.laserpay.pdei.persistence.entity.MerchantEntity;
import com.laserpay.pdei.persistence.entity.OrderEntity;
import com.laserpay.pdei.persistence.entity.OrderLineEntity;
import com.laserpay.pdei.persistence.entity.PaymentEntity;
import com.laserpay.pdei.persistence.entity.RefundEntity;
import com.laserpay.pdei.persistence.entity.ShipmentEntity;
import com.laserpay.pdei.persistence.entity.TransactionEntity;
import com.laserpay.pdei.persistence.repository.CommunicationRepository;
import com.laserpay.pdei.persistence.repository.CustomerRepository;
import com.laserpay.pdei.persistence.repository.DeliveryRepository;
import com.laserpay.pdei.persistence.repository.DisputeRepository;
import com.laserpay.pdei.persistence.repository.MerchantRepository;
import com.laserpay.pdei.persistence.repository.OrderLineRepository;
import com.laserpay.pdei.persistence.repository.OrderRepository;
import com.laserpay.pdei.persistence.repository.PaymentRepository;
import com.laserpay.pdei.persistence.repository.RefundRepository;
import com.laserpay.pdei.persistence.repository.ShipmentRepository;
import com.laserpay.pdei.persistence.repository.TransactionRepository;
import org.mockito.Mockito;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * In-memory Spring Data repositories, backed by Mockito stubs over a {@link LinkedHashMap}.
 *
 * <p>Handler behaviour - watermarks, rollups, stub creation - is pure logic over a key/value store,
 * so testing it against a map is both faster and clearer than standing up Postgres. The SQL itself
 * (constraints, the {@code ON CONFLICT} idempotency insert, the FTS indexes) is covered by
 * {@code platform-persistence}'s Testcontainers tests, which is where it belongs.
 */
public final class Repositories {

    private Repositories() {
    }

    /** Backing store handed out alongside a stubbed repository so tests can assert on it. */
    public static final class Store<T extends BaseEntity> {

        private final Map<String, T> rows = new LinkedHashMap<>();

        public Optional<T> find(String id) {
            return Optional.ofNullable(rows.get(id));
        }

        public T require(String id) {
            T value = rows.get(id);
            if (value == null) {
                throw new AssertionError("no row with id " + id + "; have " + rows.keySet());
            }
            return value;
        }

        public boolean contains(String id) {
            return rows.containsKey(id);
        }

        public int size() {
            return rows.size();
        }

        public List<T> all() {
            return new ArrayList<>(rows.values());
        }

        public List<T> matching(Predicate<T> predicate) {
            return rows.values().stream().filter(predicate).toList();
        }

        /** Seeds a row directly, for tests that need pre-existing state. */
        public void put(T entity) {
            rows.put(entity.getId(), entity);
        }
    }

    /**
     * Stubs {@code findById}, {@code save} and {@code saveAndFlush} against the store.
     *
     * <p>{@code saveAndFlush} is stubbed identically to {@code save} because this store is already
     * immediately visible - there is no deferred flush to model. ReferenceData calls the flushing
     * variant deliberately: against a real EntityManager a plain {@code save} leaves the row
     * invisible to the raw JDBC that writes evidence in the same transaction. That distinction
     * cannot be reproduced here, which is exactly why it went unnoticed until the stack ran.
     */
    private static <T extends BaseEntity> void stubCrud(JpaRepository<T, String> repository,
                                                        Store<T> store) {
        Mockito.doAnswer(invocation -> store.find(invocation.getArgument(0)))
                .when(repository).findById(anyString());
        Mockito.doAnswer(invocation -> {
            T entity = invocation.getArgument(0);
            store.put(entity);
            return entity;
        }).when(repository).save(any());
        Mockito.doAnswer(invocation -> {
            T entity = invocation.getArgument(0);
            store.put(entity);
            return entity;
        }).when(repository).saveAndFlush(any());
    }

    // --- factories ------------------------------------------------------------------------------

    public static MerchantRepository merchants(Store<MerchantEntity> store) {
        MerchantRepository repository = mock(MerchantRepository.class);
        stubCrud(repository, store);
        return repository;
    }

    public static CustomerRepository customers(Store<CustomerEntity> store) {
        CustomerRepository repository = mock(CustomerRepository.class);
        stubCrud(repository, store);
        return repository;
    }

    public static TransactionRepository transactions(Store<TransactionEntity> store) {
        TransactionRepository repository = mock(TransactionRepository.class);
        stubCrud(repository, store);
        return repository;
    }

    public static PaymentRepository payments(Store<PaymentEntity> store) {
        PaymentRepository repository = mock(PaymentRepository.class);
        stubCrud(repository, store);
        when(repository.findByTransactionId(anyString())).thenAnswer(invocation -> {
            String transactionId = invocation.getArgument(0);
            return store.matching(payment -> transactionId.equals(payment.getTransactionId()));
        });
        return repository;
    }

    public static RefundRepository refunds(Store<RefundEntity> store) {
        RefundRepository repository = mock(RefundRepository.class);
        stubCrud(repository, store);
        when(repository.sumProcessedAmountMinor(anyString())).thenAnswer(invocation -> {
            String transactionId = invocation.getArgument(0);
            return store.matching(refund -> transactionId.equals(refund.getTransactionId())
                            && "PROCESSED".equals(refund.getStatus()))
                    .stream()
                    .mapToLong(refund -> refund.getAmount().getAmountMinor())
                    .sum();
        });
        return repository;
    }

    public static OrderRepository orders(Store<OrderEntity> store) {
        OrderRepository repository = mock(OrderRepository.class);
        stubCrud(repository, store);
        return repository;
    }

    public static OrderLineRepository orderLines(Store<OrderLineEntity> store) {
        OrderLineRepository repository = mock(OrderLineRepository.class);
        stubCrud(repository, store);
        return repository;
    }

    public static ShipmentRepository shipments(Store<ShipmentEntity> store) {
        ShipmentRepository repository = mock(ShipmentRepository.class);
        stubCrud(repository, store);
        return repository;
    }

    public static DeliveryRepository deliveries(Store<DeliveryEntity> store) {
        DeliveryRepository repository = mock(DeliveryRepository.class);
        stubCrud(repository, store);
        return repository;
    }

    public static CommunicationRepository communications(Store<CommunicationEntity> store) {
        CommunicationRepository repository = mock(CommunicationRepository.class);
        stubCrud(repository, store);
        return repository;
    }

    public static DisputeRepository disputes(Store<DisputeEntity> store) {
        DisputeRepository repository = mock(DisputeRepository.class);
        stubCrud(repository, store);
        return repository;
    }
}
