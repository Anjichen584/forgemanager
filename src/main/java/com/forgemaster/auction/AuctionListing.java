package com.forgemaster.auction;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * 一件拍品。price 为当前最高价（无人出价时等于起拍价）。
 * 竞价资金在出价时即从出价人剑币中扣除（冻结），被超越时退还上一位。
 */
public final class AuctionListing {

    private final String id;
    private final UUID seller;
    private final String sellerName;
    private final ItemStack item;
    private final long startPrice;
    /** 一口价，0 = 不设 */
    private final long buyout;
    private final long createdAt;
    private final long endsAt;

    private long price;
    private UUID bidder;
    private String bidderName = "";
    private int bids;

    public AuctionListing(String id, UUID seller, String sellerName, ItemStack item,
                          long startPrice, long buyout, long price, long createdAt, long endsAt) {
        this.id = id;
        this.seller = seller;
        this.sellerName = sellerName == null ? "?" : sellerName;
        this.item = item;
        this.startPrice = startPrice;
        this.buyout = buyout;
        this.price = price;
        this.createdAt = createdAt;
        this.endsAt = endsAt;
    }

    public String id() {
        return id;
    }

    public UUID seller() {
        return seller;
    }

    public String sellerName() {
        return sellerName;
    }

    public ItemStack item() {
        return item;
    }

    public long startPrice() {
        return startPrice;
    }

    public long buyout() {
        return buyout;
    }

    public boolean hasBuyout() {
        return buyout > 0;
    }

    public long createdAt() {
        return createdAt;
    }

    public long endsAt() {
        return endsAt;
    }

    public long price() {
        return price;
    }

    public void price(long value) {
        this.price = value;
    }

    public UUID bidder() {
        return bidder;
    }

    public boolean hasBidder() {
        return bidder != null;
    }

    public String bidderName() {
        return bidderName;
    }

    public void setBidder(UUID uuid, String name) {
        this.bidder = uuid;
        this.bidderName = name == null ? "?" : name;
    }

    public int bids() {
        return bids;
    }

    public void bids(int value) {
        this.bids = value;
    }

    public boolean expired(long now) {
        return now >= endsAt;
    }
}
