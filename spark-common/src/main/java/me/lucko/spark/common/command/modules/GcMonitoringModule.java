/*
 * This file is part of spark.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package me.lucko.spark.common.command.modules;

import com.sun.management.GarbageCollectionNotificationInfo;
import me.lucko.spark.common.SparkPlatform;
import me.lucko.spark.common.command.Command;
import me.lucko.spark.common.command.CommandModule;
import me.lucko.spark.common.command.CommandResponseHandler;
import me.lucko.spark.common.monitor.memory.GarbageCollectionMonitor;
import me.lucko.spark.common.monitor.memory.GarbageCollectorStatistics;
import me.lucko.spark.common.util.FormatUtil;
import net.kyori.text.Component;
import net.kyori.text.TextComponent;
import net.kyori.text.format.TextColor;
import net.kyori.text.format.TextDecoration;

import java.lang.management.MemoryUsage;
import java.text.DecimalFormat;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class GcMonitoringModule implements CommandModule {
    private static final DecimalFormat df = new DecimalFormat("#.##");

    /** The gc monitoring instance currently running, if any */
    private ReportingGcMonitor activeGcMonitor = null;

    @Override
    public void close() {
        if (this.activeGcMonitor != null) {
            this.activeGcMonitor.close();
            this.activeGcMonitor = null;
        }
    }

    @Override
    public void registerCommands(Consumer<Command> consumer) {
        consumer.accept(Command.builder()
                .aliases("gc")
                .executor((platform, sender, resp, arguments) -> {
                    resp.replyPrefixed(TextComponent.of("Calculating GC statistics..."));

                    List<Component> report = new LinkedList<>();
                    report.add(TextComponent.empty());
                    report.add(TextComponent.builder("")
                            .append(TextComponent.builder(">").color(TextColor.DARK_GRAY).decoration(TextDecoration.BOLD, true).build())
                            .append(TextComponent.space())
                            .append(TextComponent.of("Garbage Collector statistics", TextColor.GOLD))
                            .build()
                    );

                    long serverUptime = System.currentTimeMillis() - platform.getServerNormalOperationStartTime();
                    Map<String, GarbageCollectorStatistics> collectorStats = GarbageCollectorStatistics.pollStatsSubtractInitial(platform.getStartupGcStatistics());

                    for (Map.Entry<String, GarbageCollectorStatistics> collector : collectorStats.entrySet()) {
                        String collectorName = collector.getKey();
                        double collectionTime = collector.getValue().getCollectionTime();
                        long collectionCount = collector.getValue().getCollectionCount();

                        report.add(TextComponent.empty());

                        if (collectionCount == 0) {
                            report.add(TextComponent.builder("    ")
                                    .append(TextComponent.of(collectorName + " collector:", TextColor.GRAY))
                                    .build()
                            );
                            report.add(TextComponent.builder("      ")
                                    .append(TextComponent.of(0, TextColor.WHITE))
                                    .append(TextComponent.of(" collections", TextColor.GRAY))
                                    .build()
                            );
                            continue;
                        }

                        double averageCollectionTime = collectionTime / collectionCount;
                        double averageFrequency = (serverUptime - collectionTime) / collectionCount;

                        report.add(TextComponent.builder("    ")
                                .append(TextComponent.of(collectorName + " collector:", TextColor.GRAY))
                                .build()
                        );
                        report.add(TextComponent.builder("      ")
                                .append(TextComponent.of(df.format(averageCollectionTime), TextColor.GOLD))
                                .append(TextComponent.of(" ms avg", TextColor.GRAY))
                                .append(TextComponent.of(", ", TextColor.DARK_GRAY))
                                .append(TextComponent.of(collectionCount, TextColor.WHITE))
                                .append(TextComponent.of(" total collections", TextColor.GRAY))
                                .build()
                        );
                        report.add(TextComponent.builder("      ")
                                .append(TextComponent.of(formatTime((long) averageFrequency), TextColor.WHITE))
                                .append(TextComponent.of(" avg frequency", TextColor.GRAY))
                                .build()
                        );
                    }

                    if (report.size() == 1) {
                        resp.replyPrefixed(TextComponent.of("No garbage collectors are reporting data."));
                    } else {
                        report.forEach(resp::reply);
                    }
                })
                .build()
        );

        consumer.accept(Command.builder()
                .aliases("gcmonitor", "gcmonitoring")
                .executor((platform, sender, resp, arguments) -> {
                    if (this.activeGcMonitor == null) {
                        this.activeGcMonitor = new ReportingGcMonitor(platform, resp);
                        resp.broadcastPrefixed(TextComponent.of("GC monitor enabled."));
                    } else {
                        close();
                        resp.broadcastPrefixed(TextComponent.of("GC monitor disabled."));
                    }
                })
                .build()
        );
    }

    private static String formatTime(long millis) {
        if (millis <= 0) {
            return "0ms";
        }

        long second = millis / 1000;
        //millis = millis % 1000;
        long minute = second / 60;
        second = second % 60;

        StringBuilder sb = new StringBuilder();
        if (minute != 0) {
            sb.append(minute).append("m ");
        }
        if (second != 0) {
            sb.append(second).append("s ");
        }
        //if (millis != 0) {
        //    sb.append(millis).append("ms");
        //}

        return sb.toString().trim();
    }

    private static class ReportingGcMonitor extends GarbageCollectionMonitor implements GarbageCollectionMonitor.Listener {
        private final SparkPlatform platform;
        private final CommandResponseHandler resp;

        ReportingGcMonitor(SparkPlatform platform, CommandResponseHandler resp) {
            this.platform = platform;
            this.resp = resp;
            addListener(this);
        }

        protected void sendMessage(Component message) {
            this.resp.broadcastPrefixed(message);
        }

        @Override
        public void onGc(GarbageCollectionNotificationInfo data) {
            String gcType;
            if (data.getGcAction().equals("end of minor GC")) {
                gcType = "Young Gen";
            } else if (data.getGcAction().equals("end of major GC")) {
                gcType = "Old Gen";
            } else {
                gcType = data.getGcAction();
            }

            String gcCause = data.getGcCause() != null ? " (cause = " + data.getGcCause() + ")" : "";

            Map<String, MemoryUsage> beforeUsages = data.getGcInfo().getMemoryUsageBeforeGc();
            Map<String, MemoryUsage> afterUsages = data.getGcInfo().getMemoryUsageAfterGc();

            this.platform.getPlugin().executeAsync(() -> {
                List<Component> report = new LinkedList<>();
                report.add(CommandResponseHandler.applyPrefix(TextComponent.builder("").color(TextColor.GRAY)
                        .append(TextComponent.of(gcType + " "))
                        .append(TextComponent.of("GC", TextColor.RED))
                        .append(TextComponent.of(" lasting "))
                        .append(TextComponent.of(df.format(data.getGcInfo().getDuration()), TextColor.GOLD))
                        .append(TextComponent.of(" ms." + gcCause))
                        .build()
                ));

                for (Map.Entry<String, MemoryUsage> entry : afterUsages.entrySet()) {
                    String type = entry.getKey();
                    MemoryUsage after = entry.getValue();
                    MemoryUsage before = beforeUsages.get(type);

                    if (before == null) {
                        continue;
                    }

                    long diff = before.getUsed() - after.getUsed();
                    if (diff == 0) {
                        continue;
                    }

                    if (diff > 0) {
                        report.add(TextComponent.builder("  ")
                                .append(TextComponent.of(FormatUtil.formatBytes(diff), TextColor.GOLD))
                                .append(TextComponent.of(" freed from ", TextColor.DARK_GRAY))
                                .append(TextComponent.of(type, TextColor.GRAY))
                                .build()
                        );
                        report.add(TextComponent.builder("    ")
                                .append(TextComponent.of(FormatUtil.formatBytes(before.getUsed()), TextColor.GRAY))
                                .append(TextComponent.of(" → ", TextColor.DARK_GRAY))
                                .append(TextComponent.of(FormatUtil.formatBytes(after.getUsed()), TextColor.GRAY))
                                .append(TextComponent.space())
                                .append(TextComponent.of("(", TextColor.DARK_GRAY))
                                .append(TextComponent.of(FormatUtil.percent(diff, before.getUsed()), TextColor.WHITE))
                                .append(TextComponent.of(")", TextColor.DARK_GRAY))
                                .build()
                        );
                    } else {
                        report.add(TextComponent.builder("  ")
                                .append(TextComponent.of(FormatUtil.formatBytes(-diff), TextColor.GOLD))
                                .append(TextComponent.of(" moved to ", TextColor.DARK_GRAY))
                                .append(TextComponent.of(type, TextColor.GRAY))
                                .build()
                        );
                        report.add(TextComponent.builder("    ")
                                .append(TextComponent.of(FormatUtil.formatBytes(before.getUsed()), TextColor.GRAY))
                                .append(TextComponent.of(" → ", TextColor.DARK_GRAY))
                                .append(TextComponent.of(FormatUtil.formatBytes(after.getUsed()), TextColor.GRAY))
                                .build()
                        );
                    }
                }

                report.forEach(this.resp::broadcast);
            });
        }

    }
}
