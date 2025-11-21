package me.miyeoukman.relaybuild;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class RelayBuild extends JavaPlugin implements Listener, CommandExecutor {

    // 변수 설정
    private Location locA; // 건축방
    private Location locB; // 대기방
    private int timeN; // 건축 시간 (초)
    private int maxRoundM; // 총 라운드 수
    private String playerTag; // 플레이어 태그

    // 게임 상태 관리
    private boolean isRunning = false;
    private List<UUID> participants = new ArrayList<>();
    private int currentRound = 0;
    private int currentIndex = 0;
    private String currentTopic = "";

    // 인벤토리 승계용
    private ItemStack[] savedContents;
    private ItemStack[] savedArmor;

    // 현재 건축 가능한 플레이어와 상태
    private UUID currentBuilder = null;
    private boolean canBuild = false;

    @Override
    public void onEnable() {
        // Config 설정
        saveDefaultConfig();
        FileConfiguration config = getConfig();

        // Config에 기본값이 없으면 설정
        config.addDefault("N", 60); // 기본 건축 시간 60초
        config.addDefault("M", 2);  // 기본 2라운드
        config.addDefault("PlayerTag", "builder");
        config.options().copyDefaults(true);
        saveConfig();

        // 변수 로드
        timeN = config.getInt("N");
        maxRoundM = config.getInt("M");
        playerTag = config.getString("PlayerTag");

        // 명령어 및 이벤트 등록
        getCommand("relay").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);

        getLogger().info("RelayBuild 플러그인이 활성화되었습니다.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) return false;
        Player p = (Player) sender;

        if (args.length == 0) return false;

        if (args[0].equalsIgnoreCase("setA")) {
            locA = p.getLocation();
            p.sendMessage("§a[Relay] §f현재 위치가 §e건축방(A)§f으로 설정되었습니다.");
            return true;
        } else if (args[0].equalsIgnoreCase("setB")) {
            locB = p.getLocation();
            p.sendMessage("§a[Relay] §f현재 위치가 §e대기방(B)§f으로 설정되었습니다.");
            return true;
        } else if (args[0].equalsIgnoreCase("start")) {
            if (locA == null || locB == null) {
                p.sendMessage("§c[Relay] A지점과 B지점을 먼저 설정해주세요.");
                return true;
            }
            if (args.length < 2) {
                p.sendMessage("§c[Relay] 주제를 입력해주세요. 사용법: /relay start <주제>");
                return true;
            }

            // 주제 합치기 (띄어쓰기 포함)
            StringBuilder sb = new StringBuilder();
            for(int i=1; i<args.length; i++) sb.append(args[i]).append(" ");
            currentTopic = sb.toString().trim();

            startGame(p);
            return true;
        }
        return false;
    }

    private void startGame(Player starter) {
        if (isRunning) {
            starter.sendMessage("§c[Relay] 이미 게임이 진행 중입니다.");
            return;
        }

        // 참가자 모집
        participants.clear();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getScoreboardTags().contains(playerTag)) {
                participants.add(p.getUniqueId());
                // 초기화 및 대기방 이동
                p.getInventory().clear();
                p.teleport(locB);
                p.sendMessage("§a[Relay] §f게임이 시작되었습니다! 잠시 대기해주세요.");
            }
        }

        if (participants.isEmpty()) {
            starter.sendMessage("§c[Relay] 태그(" + playerTag + ")를 가진 플레이어가 없습니다.");
            return;
        }

        // 랜덤 셔플
        Collections.shuffle(participants);

        isRunning = true;
        currentRound = 1;
        currentIndex = 0;
        savedContents = null;
        savedArmor = null;

        new BukkitRunnable() {
            int count = 3;

            @Override
            public void run() {
                if (!isRunning) { cancel(); return; } // 안전장치

                if (count > 0) {
                    // 참가자 전원에게 타이틀과 소리 출력
                    for (UUID uuid : participants) {
                        Player p = Bukkit.getPlayer(uuid);
                        if (p != null && p.isOnline()) {
                            p.sendTitle("§c" + count, "", 0, 20, 10);
                            // 피치(Pitch) 2.0은 높은 '땡!' 소리입니다.
                            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 10f, 2.0f);
                        }
                    }
                    count--;
                } else {
                    // 카운트다운 끝 -> 게임 시작 알림 및 턴 시작
                    for (UUID uuid : participants) {
                        Player p = Bukkit.getPlayer(uuid);
                        if (p != null && p.isOnline()) {
                            p.sendTitle("§aSTART!", "", 0, 20, 10);
                            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 10f, 1.0f);
                        }
                    }
                    startTurn(); // 여기서 게임 실질적 시작
                    cancel();
                }
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    private void startTurn() {
        if (currentIndex >= participants.size()) {
            // 한 라운드 종료
            currentRound++;
            currentIndex = 0;

            if (currentRound > maxRoundM) {
                endGame();
                return;
            }
        }

        UUID builderUUID = participants.get(currentIndex);
        Player p = Bukkit.getPlayer(builderUUID);

        // 플레이어가 나갔을 경우 예외 처리 (다음 사람으로 넘어감)
        if (p == null || !p.isOnline()) {
            currentIndex++;
            startTurn();
            return;
        }

        currentBuilder = builderUUID;

        // A지점으로 이동
        p.teleport(locA);

        // 1회차일 경우 주제 알려주기
        if (currentRound == 1) {
            p.sendTitle("§e주제: " + currentTopic, "§7멋진 건축물을 만들어보세요!", 10, 70, 20);
            p.sendMessage("§a[Relay] §e주제: §f" + currentTopic);
        }

        // 인벤토리 승계 (첫 라운드 첫 타자는 제외)
        if (!(currentRound == 1 && currentIndex == 0)) {
            if (savedContents != null) p.getInventory().setContents(savedContents);
            if (savedArmor != null) p.getInventory().setArmorContents(savedArmor);
        }

        // 1회차 첫 번째 선수만 10초 준비 시간 가짐
        if (currentRound == 1 && currentIndex == 0) {
            canBuild = false; // 건축 불가
            new BukkitRunnable() {
                int count = 10;
                @Override
                public void run() {
                    if (!isRunning) { cancel(); return; }

                    if (count > 0) {
                        p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent("§e준비 시간: " + count + "초 (건축 불가)"));
                        count--;
                    } else {
                        // 10초 끝, 건축 시작
                        startBuildTime(p);
                        cancel();
                    }
                }
            }.runTaskTimer(this, 0L, 20L);
        } else {
            // 그 외에는 바로 건축 시작
            startBuildTime(p);
        }
    }

    private void startBuildTime(Player p) {
        canBuild = true; // 건축 허용
        p.sendMessage("§a[Relay] §f건축을 시작하세요!");

        new BukkitRunnable() {
            int count = timeN;
            @Override
            public void run() {
                if (!isRunning) { cancel(); return; }

                if (count > 0) {
                    p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent("§a남은 시간: " + count + "초"));
                    // 종료 임박 시 소리 재생
                    if (count <= 5) {
                        // p.playSound(...) // 필요하면 추가
                    }
                    count--;
                } else {
                    // 시간 종료
                    finishTurn(p);
                    cancel();
                }
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    private void finishTurn(Player p) {
        canBuild = false;
        currentBuilder = null;

        // 인벤토리 저장 (다음 사람을 위해)
        savedContents = p.getInventory().getContents();
        savedArmor = p.getInventory().getArmorContents();

        // 인벤토리 비우고 대기방으로 이동
        p.getInventory().clear();
        p.teleport(locB);
        p.sendMessage("§e[Relay] §f수고하셨습니다! 다음 사람에게 턴이 넘어갑니다.");

        // 다음 턴 진행
        currentIndex++;
        startTurn();
    }

    private void endGame() {
        isRunning = false;
        currentBuilder = null;
        Bukkit.broadcastMessage("§a[Relay] §f모든 회차가 종료되었습니다! 결과물을 확인하세요.");

        // 모든 참가자 A지점으로 소환
        for (UUID uuid : participants) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                p.teleport(locA);
                // p.setGameMode(GameMode.SPECTATOR); // 필요 시 관전 모드 변경
            }
        }
    }

    // --- 이벤트 리스너 (건축 제한) ---

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent e) {
        if (!isRunning) return; // 게임 중 아니면 상관 없음
        handleBuildEvent(e.getPlayer(), e);
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent e) {
        if (!isRunning) return;
        handleBuildEvent(e.getPlayer(), e);
    }

    private void handleBuildEvent(Player p, org.bukkit.event.Cancellable e) {
        // 참가자가 아니면 간섭 불가 (옵션)
        if (!participants.contains(p.getUniqueId())) {
            if (p.isOp()) return; // OP는 간섭 가능하게
            e.setCancelled(true);
            return;
        }

        // 현재 건축가인지 확인
        if (!p.getUniqueId().equals(currentBuilder)) {
            e.setCancelled(true); // 내 차례 아님
            p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent("§c지금은 당신의 차례가 아닙니다."));
            return;
        }

        // 준비 시간(10초)인지 확인
        if (!canBuild) {
            e.setCancelled(true); // 아직 준비 시간임
            p.sendMessage("§c준비 시간에는 블럭을 설치/파괴할 수 없습니다.");
        }
    }
}