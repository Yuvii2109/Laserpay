package com.laserpay.pdei.persistence.entity;

import com.laserpay.pdei.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The ONLY way money is stored in PDEI: {@code amount_minor BIGINT} + {@code currency CHAR(3)}.
 *
 * <p>Non-negotiable rule 4 of the platform contract: no {@code float}, {@code double} or
 * {@code BigDecimal} ever touches a monetary value. Entities embed this type with
 * {@code @AttributeOverride}s naming the two physical columns, and convert to and from the
 * shared {@link Money} record at the service boundary.
 *
 * <p>Mutable with a no-arg constructor because JPA requires it; treat instances as values.
 */
@Embeddable
public class MoneyEmbeddable {

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    /**
     * ISO-4217 alphabetic code. Declared as CHAR(3) in DDL, so the JDBC type is pinned to
     * CHAR: without this Hibernate would expect VARCHAR and schema validation would fail.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3, columnDefinition = "char(3)")
    private String currency;

    public MoneyEmbeddable() {
    }

    public MoneyEmbeddable(long amountMinor, String currency) {
        this.amountMinor = amountMinor;
        this.currency = currency;
    }

    /** Converter: shared {@link Money} record to the embeddable column pair. */
    public static MoneyEmbeddable of(Money money) {
        Objects.requireNonNull(money, "money");
        return new MoneyEmbeddable(money.amountMinor(), money.currency());
    }

    /** Null-tolerant variant for optional monetary columns (e.g. {@code evidence.amount_minor}). */
    public static MoneyEmbeddable ofNullable(Money money) {
        return money == null ? null : of(money);
    }

    public static MoneyEmbeddable of(long amountMinor, String currency) {
        return new MoneyEmbeddable(amountMinor, currency);
    }

    public static MoneyEmbeddable zero(String currency) {
        return new MoneyEmbeddable(0L, currency);
    }

    /** Converter: embeddable column pair back to the shared {@link Money} record. */
    public Money toMoney() {
        return Money.of(amountMinor, currency);
    }

    /** Null-tolerant static counterpart of {@link #toMoney()} for optional columns. */
    public static Money toMoney(MoneyEmbeddable embeddable) {
        return embeddable == null || embeddable.currency == null ? null : embeddable.toMoney();
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public void setAmountMinor(long amountMinor) {
        this.amountMinor = amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MoneyEmbeddable other)) {
            return false;
        }
        return amountMinor == other.amountMinor && Objects.equals(currency, other.currency);
    }

    @Override
    public int hashCode() {
        return Objects.hash(amountMinor, currency);
    }

    @Override
    public String toString() {
        return currency + " " + amountMinor + " (minor units)";
    }
}
