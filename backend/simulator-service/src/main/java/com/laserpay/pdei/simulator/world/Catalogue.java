package com.laserpay.pdei.simulator.world;

import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Fixed pools of names, products, cities and carriers the generator draws from.
 *
 * <p>Everything here is a constant list indexed by a seeded {@link RandomGenerator}, never a
 * random string. Constants give a world that reads like a real one - a merchant called
 * "Northwind Traders", an order for a "Cold brew coffee maker" delivered by "Bluedart" - which
 * matters more than it sounds: a demo of an evidence platform is unreadable when every field is
 * {@code a7f3c2}, and an investigator cannot tell a plausible timeline from an implausible one.
 *
 * <p>Prices are minor units ({@code paise} for INR). No floating-point money anywhere, including
 * in test fixtures.
 */
public final class Catalogue {

    /** Products a merchant can sell: SKU stem, display name, unit price in minor units. */
    public record Product(String sku, String name, long unitAmountMinor) {
    }

    static final List<String> MERCHANT_NAMES = List.of(
            "Northwind Traders", "Ashoka Home Goods", "Meridian Outfitters", "Bluebird Electronics",
            "Saffron Kitchenware", "Cobalt Cycles", "Peregrine Books", "Lantern Coffee Roasters",
            "Verdant Garden Supply", "Kestrel Audio", "Marigold Textiles", "Ironwood Furniture");

    static final List<String> MERCHANT_CATEGORIES = List.of(
            "5399", "5712", "5732", "5942", "5651", "5941", "5814", "5499");

    static final List<String> FIRST_NAMES = List.of(
            "Priya", "Arjun", "Meera", "Rohan", "Ananya", "Vikram", "Sneha", "Karthik",
            "Divya", "Rahul", "Nisha", "Aditya", "Kavya", "Sanjay", "Ishita", "Farhan");

    static final List<String> LAST_NAMES = List.of(
            "Raman", "Sharma", "Iyer", "Menon", "Kulkarni", "Nair", "Banerjee", "Desai",
            "Chatterjee", "Reddy", "Pillai", "Sengupta", "Joshi", "Gupta");

    static final List<String> CITIES = List.of(
            "Bengaluru", "Mumbai", "Pune", "Hyderabad", "Chennai", "Kolkata", "Ahmedabad", "Jaipur");

    static final List<String> STREETS = List.of(
            "Residency Road", "MG Road", "Linking Road", "Park Street", "Anna Salai",
            "Koregaon Park", "Banjara Hills", "Civil Lines");

    static final List<String> CARRIERS = List.of(
            "Bluedart", "Delhivery", "Ecom Express", "XpressBees", "India Post");

    static final List<Product> PRODUCTS = List.of(
            new Product("KTC-1001", "Cold brew coffee maker", 349_900L),
            new Product("KTC-1002", "Cast iron skillet 26cm", 219_900L),
            new Product("ELC-2001", "Noise cancelling headphones", 1_299_900L),
            new Product("ELC-2002", "Mechanical keyboard 87-key", 749_900L),
            new Product("ELC-2003", "Portable SSD 1TB", 899_900L),
            new Product("HOM-3001", "Linen bedsheet set queen", 429_900L),
            new Product("HOM-3002", "Ceramic table lamp", 189_900L),
            new Product("OUT-4001", "Trail running shoes", 679_900L),
            new Product("OUT-4002", "40L hiking backpack", 549_900L),
            new Product("BKS-5001", "Hardcover boxed set", 259_900L),
            new Product("CYC-6001", "Alloy road bike frame", 2_899_900L),
            new Product("GRD-7001", "Self-watering planter set", 149_900L));

    static final List<String> COMMUNICATION_CHANNELS = List.of("EMAIL", "CHAT", "PHONE");

    private Catalogue() {
    }

    /** Uniform pick from a list using the seeded generator. */
    public static <T> T pick(RandomGenerator random, List<T> pool) {
        return pool.get(random.nextInt(pool.size()));
    }
}
