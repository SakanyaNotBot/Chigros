package com.wuying.phigros.game;

import com.fasterxml.jackson.annotation.JsonProperty;

public class SkinConfig {
    @JsonProperty("name") public String name;
    @JsonProperty("author") public String author;
    @JsonProperty("description") public String description;
    @JsonProperty("holdAtlas") public int[] holdAtlas;
    @JsonProperty("holdAtlasMH") public int[] holdAtlasMH;
    @JsonProperty("hitFx") public int[] hitFx;
    @JsonProperty("hitFxScale") public float hitFxScale = 1.0f;
    @JsonProperty("hitFxDuration") public float hitFxDuration = 0.5f;
    @JsonProperty("hitFxRotate") public boolean hitFxRotate = false;
    @JsonProperty("hideParticles") public boolean hideParticles = false;
    @JsonProperty("hitFxTinted") public boolean hitFxTinted = true;
    @JsonProperty("holdKeepHead") public boolean holdKeepHead = false;
    @JsonProperty("holdRepeat") public boolean holdRepeat = false;
    @JsonProperty("holdCompact") public boolean holdCompact = false;
    @JsonProperty("colorPerfect") public String colorPerfect;
    @JsonProperty("colorGood") public String colorGood;
}
