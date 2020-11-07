/*
 * Copyright 2014-2016 Groupon, Inc
 * Copyright 2014-2016 The Billing Project, LLC
 *
 * The Billing Project licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.killbill.billing.invoice.usage;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;

import org.joda.time.LocalDate;
import org.killbill.billing.callcontext.InternalCallContext;
import org.killbill.billing.catalog.api.BillingPeriod;
import org.killbill.billing.catalog.api.Usage;
import org.killbill.billing.invoice.api.InvoiceItem;
import org.killbill.billing.invoice.dao.InvoiceDao;
import org.killbill.billing.invoice.dao.InvoiceTrackingModelDao;
import org.killbill.billing.invoice.generator.InvoiceDateUtils;
import org.killbill.billing.invoice.generator.InvoiceWithMetadata.TrackingRecordId;
import org.killbill.billing.usage.InternalUserApi;
import org.killbill.billing.usage.api.RawUsageRecord;
import org.killbill.billing.util.config.definition.InvoiceConfig;
import org.killbill.clock.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.collect.Ordering;

public class RawUsageOptimizer {




    private static final Ordering<InvoiceItem> USAGE_ITEM_ORDERING = Ordering.natural()
                                                                             .onResultOf(new Function<InvoiceItem, Comparable>() {
                                                                                 @Override
                                                                                 public Comparable apply(final InvoiceItem invoiceItem) {
                                                                                     return invoiceItem.getEndDate();
                                                                                 }
                                                                             });

    private static final Logger log = LoggerFactory.getLogger(RawUsageOptimizer.class);

    private final InternalUserApi usageApi;
    private final InvoiceConfig config;
    private final InvoiceDao invoiceDao;
    private final Clock clock;

    @Inject
    public RawUsageOptimizer(final InvoiceConfig config, final InvoiceDao invoiceDao, final InternalUserApi usageApi, final Clock clock) {
        this.usageApi = usageApi;
        this.config = config;
        this.invoiceDao = invoiceDao;
        this.clock = clock;
    }

    public RawUsageOptimizerResult getInArrearUsage(final LocalDate firstEventStartDate, final LocalDate targetDate, final Map<String, Usage> knownUsage, final InternalCallContext internalCallContext) {
        final int configRawUsagePreviousPeriod = config.getMaxRawUsagePreviousPeriod(internalCallContext);
        final LocalDate optimizedStartDate = configRawUsagePreviousPeriod >= 0 ? getOptimizedRawUsageStartDate(firstEventStartDate, targetDate, knownUsage, internalCallContext) : firstEventStartDate;
        log.debug("RawUsageOptimizerResult accountRecordId='{}', configRawUsagePreviousPeriod='{}', firstEventStartDate='{}', optimizedStartDate='{}',  targetDate='{}'",
                  internalCallContext.getAccountRecordId(), configRawUsagePreviousPeriod, firstEventStartDate, optimizedStartDate, targetDate);
        final List<RawUsageRecord> rawUsageData = usageApi.getRawUsageForAccount(optimizedStartDate, targetDate, internalCallContext);

        final List<InvoiceTrackingModelDao> trackingIds = invoiceDao.getTrackingsByDateRange(optimizedStartDate, targetDate, internalCallContext);
        final Set<TrackingRecordId> existingTrackingIds = new HashSet<TrackingRecordId>();
        for (final InvoiceTrackingModelDao invoiceTrackingModelDao : trackingIds) {
            existingTrackingIds.add(new TrackingRecordId(invoiceTrackingModelDao.getTrackingId(), invoiceTrackingModelDao.getInvoiceId(), invoiceTrackingModelDao.getSubscriptionId(), invoiceTrackingModelDao.getUnitType(), invoiceTrackingModelDao.getRecordDate()));
        }
        return new RawUsageOptimizerResult(optimizedStartDate, rawUsageData, existingTrackingIds);
    }


    @VisibleForTesting
    LocalDate getOptimizedRawUsageStartDate(final LocalDate firstEventStartDate, final LocalDate targetDate, final Map<String, Usage> knownUsage, final InternalCallContext internalCallContext) {

        final LocalDate utcToday = clock.getUTCToday();
        final LocalDate minTodayTargetDate = utcToday.compareTo(targetDate) < 0 ? utcToday : targetDate;
        final Map<BillingPeriod, LocalDate> perBillingPeriodMostRecentConsumableInArrearItemEndDate = new HashMap<>();

        // Extract all usage billing period known in that catalog
        final Collection<BillingPeriod> knownUsageBillingPeriod = new HashSet<BillingPeriod>();
        for (final Usage usage : knownUsage.values()) {
            knownUsageBillingPeriod.add(usage.getBillingPeriod());
        }

        for (final BillingPeriod bp : knownUsageBillingPeriod) {
            // The potential start date of an item with billing period BP that would not on minTodayTargetDate
            // E.g with org.killbill.invoice.readMaxRawUsagePreviousPeriod=0, any optimized date prior or equal to this this would return
            // enough usage item to bill previous period.
            //
            final LocalDate perBPStartDate = InvoiceDateUtils.recedeByNPeriods(minTodayTargetDate, bp, 1);
            perBillingPeriodMostRecentConsumableInArrearItemEndDate.put(bp, perBPStartDate);
        }


        // Extract the min from all the dates
        LocalDate targetStartDate = targetDate;
        for (final BillingPeriod bp : perBillingPeriodMostRecentConsumableInArrearItemEndDate.keySet()) {
            final LocalDate tmp = perBillingPeriodMostRecentConsumableInArrearItemEndDate.get(bp);
            final LocalDate targetBillingPeriodDate = InvoiceDateUtils.recedeByNPeriods(tmp, bp, config.getMaxRawUsagePreviousPeriod(internalCallContext));
            if (targetBillingPeriodDate.compareTo(targetStartDate) < 0) {
                targetStartDate = targetBillingPeriodDate;
            }
        }
        // Make sure we don't end up with a date lower than the first billing event date
        final LocalDate result = targetStartDate.compareTo(firstEventStartDate) > 0 ? targetStartDate : firstEventStartDate;
        return result;
    }

    private boolean containsNullEntries(final Map<BillingPeriod, LocalDate> entries) {
        for (final LocalDate entry : entries.values()) {
            if (entry == null) {
                return true;
            }
        }
        return false;
    }

    public static class RawUsageOptimizerResult {

        private final LocalDate rawUsageStartDate;
        private final List<RawUsageRecord> rawUsage;
        private final Set<TrackingRecordId> existingTrackingIds;

        public RawUsageOptimizerResult(final LocalDate rawUsageStartDate, final List<RawUsageRecord> rawUsage, final Set<TrackingRecordId> existingTrackingIds) {
            this.rawUsageStartDate = rawUsageStartDate;
            this.rawUsage = rawUsage;
            this.existingTrackingIds = existingTrackingIds;
        }

        public LocalDate getRawUsageStartDate() {
            return rawUsageStartDate;
        }

        public List<RawUsageRecord> getRawUsage() {
            return rawUsage;
        }

        public Set<TrackingRecordId> getExistingTrackingIds() {
            return existingTrackingIds;
        }
    }
}
