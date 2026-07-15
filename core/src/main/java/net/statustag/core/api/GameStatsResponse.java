package net.statustag.core.api;

import com.google.gson.annotations.SerializedName;

public class GameStatsResponse {

  @SerializedName("last_online")
  private String lastOnline;

  public String getLastOnline() {
    return this.lastOnline;
  }
}
