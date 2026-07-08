package com.mysc.mydoc.api;

import com.mysc.mydoc.common.ValidationException;
import com.mysc.mydoc.domain.KnowledgeSetting;
import com.mysc.mydoc.domain.SlackChannelConfig;
import com.mysc.mydoc.ingest.SlackGateway;
import com.mysc.mydoc.repository.KnowledgeSettingRepository;
import com.mysc.mydoc.repository.SlackChannelConfigRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
public class ArchiveConfigController {
    private final ObjectProvider<SlackGateway> slack;
    private final SlackChannelConfigRepository channelConfigs;
    private final KnowledgeSettingRepository settings;

    public ArchiveConfigController(
            ObjectProvider<SlackGateway> slack,
            SlackChannelConfigRepository channelConfigs,
            KnowledgeSettingRepository settings
    ) {
        this.slack = slack;
        this.channelConfigs = channelConfigs;
        this.settings = settings;
    }

    public record ChannelResponse(String channelId, String channelName, boolean isPrivate, boolean archiveEnabled) {}
    public record ChannelListResponse(List<ChannelResponse> channels) {}
    public record ChannelToggleRequest(@NotNull Boolean enabled, String channelName) {}
    public record SettingsResponse(int quietMinutes, int minMessages) {}
    public record SettingsRequest(
            @NotNull @Min(1) @Max(1440) Integer quietMinutes,
            @NotNull @Min(1) @Max(100) Integer minMessages
    ) {}

    /** 봇이 초대된 Slack 채널 + 아카이빙 on/off 상태. Slack 미설정이면 저장된 설정만 반환. */
    @GetMapping("/api/slack/channels")
    ChannelListResponse channels() {
        Map<String, ChannelResponse> merged = new LinkedHashMap<>();
        SlackGateway gateway = slack.getIfAvailable();
        if (gateway != null) {
            for (SlackGateway.SlackChannel channel : gateway.memberChannels()) {
                merged.put(channel.id(), new ChannelResponse(channel.id(), channel.name(), channel.isPrivate(), false));
            }
        }
        for (SlackChannelConfig config : channelConfigs.findAll()) {
            ChannelResponse listed = merged.get(config.getChannelId());
            merged.put(config.getChannelId(), new ChannelResponse(
                    config.getChannelId(),
                    listed != null ? listed.channelName() : config.getChannelName(),
                    listed != null && listed.isPrivate(),
                    config.isArchiveEnabled()));
        }
        return new ChannelListResponse(List.copyOf(merged.values()));
    }

    @PutMapping("/api/slack/channels/{channelId}")
    @Transactional
    ChannelResponse toggle(@PathVariable String channelId, @RequestBody ChannelToggleRequest request) {
        String name = request.channelName() != null ? request.channelName() : channelId;
        SlackChannelConfig config = channelConfigs.findById(channelId)
                .orElseGet(() -> new SlackChannelConfig(channelId, name, request.enabled()));
        config.update(name, request.enabled());
        channelConfigs.save(config);
        return new ChannelResponse(config.getChannelId(), config.getChannelName(), false, config.isArchiveEnabled());
    }

    @GetMapping("/api/knowledge/settings")
    SettingsResponse getSettings() {
        return settings.findById(KnowledgeSetting.SINGLETON_ID)
                .map(setting -> new SettingsResponse(setting.getQuietMinutes(), setting.getMinMessages()))
                .orElseThrow(() -> new ValidationException("knowledge settings row is missing"));
    }

    @PutMapping("/api/knowledge/settings")
    @Transactional
    SettingsResponse updateSettings(@RequestBody @Valid SettingsRequest request) {
        KnowledgeSetting setting = settings.findById(KnowledgeSetting.SINGLETON_ID)
                .orElseGet(() -> new KnowledgeSetting(request.quietMinutes(), request.minMessages()));
        setting.update(request.quietMinutes(), request.minMessages());
        settings.save(setting);
        return new SettingsResponse(setting.getQuietMinutes(), setting.getMinMessages());
    }
}
