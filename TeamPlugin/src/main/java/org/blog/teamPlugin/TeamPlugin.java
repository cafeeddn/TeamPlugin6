package org.blog.teamPlugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 팀 점령전 + 병과 선택 (Shift+Q 트리거)
 * - Shift+Q: "§a점령전 시작 <" 아이템을 메인핸드에 들고 있을 때만 동작
 * - IDLE → 시작/취소 GUI
 * - COLLECTING →
 *   · 개설자: '모집 관리' GUI(강제 취소 버튼)
 *   · 참가자: 병과 선택 GUI
 * - 시작자(개설자)는 즉시 참가 처리 + 병과창 자동 오픈
 * - 모집 40초 후 랜덤 2분할, 4인 미만이면 취소(스폰 이동 + 시작아이템 제거)
 * - 게임 시작 후 병과 변경 불가
 * - 기마 클래스는 말 즉시 소환/탑승, 배치 시 말째 이동
 * - 리스폰한 플레이어에게 핫바 마지막(8) 슬롯에 시작아이템 지급
 * - /tcap cancel 로도 개설자가 강제 취소 가능
 *
 * [추가]
 * - 점령: 신호기(임시: 월드 스폰) 중심 4×4, 단독 점유 시 20초마다 1점 (양팀 동시 진입 시 정지)
 * - 팀 점수: 경기 중 비표시(사이드바 제거), 종료 시 채팅으로만 결과 안내
 * - 사망/부활: 개인 20초 대기 후 팀 스폰 부활. 팀 전멸 시 마지막 사망 기준 20초 뒤 팀 전원 동시 부활
 * - 시작 카운트다운(5초), 경기 15분 제한(BossBar 타이머), 종료 시 전원 즉사 처리 후 서버 스폰 복귀 + 시작아이템 지급
 * - 기마 말 사망 시 드랍 제거
 * - 검 기마(SWORD_CAV): 말 탑승 중에만 +10% 데미지
 */
public final class TeamPlugin extends JavaPlugin implements Listener {

    /* ===== 타이틀/슬롯 ===== */
    private static final String MENU_TITLE_ROOT   = "§a팀 점령전";
    private static final String MENU_TITLE_CLASS  = "§a병과 선택";
    private static final String MENU_TITLE_ADMIN  = "§c모집 관리";

    private static final int SLOT_START  = 13;
    private static final int SLOT_CANCEL = 22;

    // 모집 관리 메뉴
    private static final int ASLOT_FORCE_CANCEL = 13;

    // 병과 선택창 (보병=윗줄, 기마=아랫줄 중앙 배치)
    private static final int CSLOT_BOW_INF   = 10; // 윗줄
    private static final int CSLOT_SWORD_INF = 12; // 윗줄
    private static final int CSLOT_LANCE_INF = 14; // 윗줄
    private static final int CSLOT_BOW_CAV   = 20; // 아랫줄
    private static final int CSLOT_SWORD_CAV = 24; // 아랫줄

    /* ===== 시작 아이템 ===== */
    private static final String  START_ITEM_NAME     = "§a점령전 시작 <";
    private static final Material START_ITEM_MATERIAL = Material.NETHER_STAR;

    /* ===== 상태 ===== */
    private Conquest current; // 동시 하나만
    private final Set<UUID> spawnedHorses = new HashSet<>(); // 정리용

    /* ===== 모집 시간(틱) ===== */
    private static final long JOIN_WINDOW_TICKS = 20L * 40; // 40초

    /* ===== 병과 ===== */
    public enum ClassType { BOW_INF, SWORD_INF, LANCE_INF, BOW_CAV, SWORD_CAV }
    enum Phase { IDLE, COLLECTING, STARTED }

    /* ===== 점령/리스폰/경기 설정 ===== */
    private static final int CAP_HALF = 2;              // 4×4 정사각형 (centerX-2..centerX+1)
    private static final int SECONDS_PER_POINT = 20;    // 단독 점유 20초 → 1점
    private static final int COUNTDOWN_SECONDS = 5;     // 시작 전 카운트다운
    private static final int RESPAWN_SECONDS   = 20;    // 사망 후 대기
    private static final long GAME_DURATION_TICKS = 20L * 60 * 15; // 15분
    private static final long GAME_DURATION_MS    = 15L * 60L * 1000L;

    private static final String TEAM_A_LABEL = "§9청팀";
    private static final String TEAM_B_LABEL = "§f백팀";

    // 경기 종료 후 리스폰 시 서버 스폰으로 돌려보내기 위한 플래그
    private final Set<UUID> endedTeleport = new HashSet<>();

    static final class Conquest {
        Phase phase = Phase.IDLE;

        UUID initiator;
        long startedAtMs;

        // 모집 스케줄
        int joinTaskId = -1;

        // 점령/경기 스케줄
        int capTaskId = -1;
        int endTaskId = -1;
        int countdownTaskId = -1;

        // 참가자/팀
        final Set<UUID> accepted = new HashSet<>();
        final Set<UUID> declined = new HashSet<>();
        final Map<UUID, Scoreboard> prevBoards = new HashMap<>();
        final Map<UUID, ClassType>  chosenClass = new HashMap<>();
        final Set<UUID> teamA = new HashSet<>();
        final Set<UUID> teamB = new HashSet<>();
        final Set<UUID> participants = new HashSet<>(); // 시작 시 고정 스냅샷

        // 점수/점령 상태
        int scoreA = 0, scoreB = 0;
        int capTickA = 0, capTickB = 0;

        // 필드 좌표(임시 값: 월드 스폰 기준)
        Location capCenter;
        Location spawnA;
        Location spawnB;

        // 생사/리스폰 관리
        final Set<UUID> dead = new HashSet<>();
        final Map<UUID, Long> deathAt = new HashMap<>();
        final Map<UUID, Integer> indivRespawnTaskId = new HashMap<>();
        Integer teamARespawnTaskId = null;
        Integer teamBRespawnTaskId = null;

        // 실시간 보드(업데이트용)
        final Map<UUID, Scoreboard> liveBoards = new HashMap<>();

        // 타이머 바
        BossBar timerBar;
        long gameEndAtMs;
    }

    @Override public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("TeamConquest Enabled");
    }
    @Override public void onDisable() { resetEvent(); }

    /* -------------------------------------------------
     * 리스폰 시 시작 아이템 지급 / 경기 종료 복귀 / 경기 중 대기 로직
     * ------------------------------------------------- */
    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();

        // 경기 종료 후 리스폰: 스폰으로 보내고 시작 아이템 지급
        if (endedTeleport.remove(p.getUniqueId())) {
            getServer().getScheduler().runTask(this, () -> {
                // 상태 정리
                p.getActivePotionEffects().forEach(pe -> p.removePotionEffect(pe.getType()));
                p.getInventory().clear();
                p.getInventory().setArmorContents(null);
                p.getInventory().setItemInOffHand(null);
                p.setHealth(Math.min(p.getMaxHealth(), 20.0));
                p.setFoodLevel(20);
                p.setSaturation(10);

                // 스폰 복귀 + 시작아이템
                p.teleport(p.getWorld().getSpawnLocation().clone().add(0, 1, 0));
                giveStartItem(p);
            });
            return;
        }


        // 게임 중 참가자는 스펙테이터 대기 → 별도 로직에서 처리
        if (current != null && current.phase == Phase.STARTED && isParticipant(p.getUniqueId())) {
            getServer().getScheduler().runTask(this, () -> {
                p.setGameMode(GameMode.SPECTATOR);
                // 대기 위치: 캡 중앙 위쪽 6블록
                if (current.capCenter != null) {
                    Location wait = current.capCenter.clone().add(0, 6, 0);
                    p.teleport(wait);
                }
            });
            // 팀 전멸 체크 & 리스폰 타이머 배치
            handleRespawnSchedulingAfterDeath(p);
        } else {
            // 일반 월드 상황
            getServer().getScheduler().runTask(this, () -> giveStartItem(p)); // 한 틱 뒤 안전
        }
    }

    private ItemStack makeStartItem() {
        ItemStack it = new ItemStack(START_ITEM_MATERIAL);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(START_ITEM_NAME);
        it.setItemMeta(m);
        return it;
    }
    private void giveStartItem(Player p) {
        p.getInventory().setItem(8, makeStartItem()); // 핫바 마지막
        p.updateInventory();
    }
    private void removeStartItem(Player p) {
        for (int i = 0; i < p.getInventory().getSize(); i++) {
            ItemStack it = p.getInventory().getItem(i);
            if (isStartItem(it)) p.getInventory().setItem(i, null);
        }
        p.updateInventory();
    }
    private boolean isStartItem(ItemStack it) {
        if (it == null || it.getType() != START_ITEM_MATERIAL) return false;
        if (!it.hasItemMeta()) return false;
        return START_ITEM_NAME.equals(it.getItemMeta().getDisplayName());
    }
    private boolean isHoldingStartItem(Player p) { return isStartItem(p.getInventory().getItemInMainHand()); }

    /* -------------------------------------------------
     * Shift+Q → 메뉴/병과/관리  (시작아이템 불필요, 드랍 항상 취소)
     * ------------------------------------------------- */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDropWhileSneaking(PlayerDropItemEvent e) {
        Player p = e.getPlayer();
        if (!p.isSneaking()) return;   // Shift 아닐 때 무시

        // ★ 항상 드랍 취소(시작아이템을 들고 있지 않아도)
        e.setCancelled(true);

        if (current == null || current.phase == Phase.IDLE) {
            // 대기 상태 → 루트 메뉴
            openRootMenu(p);
            return;
        }

        if (current.phase == Phase.COLLECTING) {
            // 개설자 → 모집 관리
            if (p.getUniqueId().equals(current.initiator)) {
                openAdminMenu(p);
                return;
            }
            // 참가자 → 병과 선택
            if (current.accepted.contains(p.getUniqueId())) {
                openClassMenu(p);
                return;
            }
            p.sendMessage("§c참가자만 병과를 선택할 수 있습니다.");
            return;
        }

        // STARTED 이후엔 병과 변경 불가
        p.sendMessage("§c게임이 이미 시작되어 병과를 바꿀 수 없습니다.");
    }


    /* -------------------------------------------------
     * GUI
     * ------------------------------------------------- */
    private void openRootMenu(Player opener) {
        Inventory inv = Bukkit.createInventory(null, 27, MENU_TITLE_ROOT);
        inv.setItem(SLOT_START, icon(Material.LIME_CONCRETE, "§a팀 점령전 시작",
                "§7모집 시간: §f40초", "§7최소 인원: §f4명(2v2)", "§e클릭하여 시작"));
        inv.setItem(SLOT_CANCEL, icon(Material.RED_CONCRETE, "§c취소", "§7창을 닫습니다."));
        opener.openInventory(inv);
    }
    private void openAdminMenu(Player opener) {
        Inventory inv = Bukkit.createInventory(null, 27, MENU_TITLE_ADMIN);
        inv.setItem(ASLOT_FORCE_CANCEL, icon(Material.BARRIER, "§c모집 즉시 취소",
                "§7현재 진행 중인 팀 점령전 모집을 즉시 취소합니다.",
                "§7모든 참가자는 스폰으로 이동되고 시작 아이템이 회수됩니다.",
                "§e개설자 전용"));
        opener.openInventory(inv);
    }
    private void openClassMenu(Player p) {
        Inventory inv = Bukkit.createInventory(null, 27, MENU_TITLE_CLASS);
        // 윗줄: 보병
        inv.setItem(CSLOT_BOW_INF,   icon(Material.BOW,              "§a활 보병"));
        inv.setItem(CSLOT_SWORD_INF, icon(Material.DIAMOND_SWORD,    "§a검 보병"));
        inv.setItem(CSLOT_LANCE_INF, icon(Material.NETHERITE_AXE,    "§a창 보병"));
        // 아랫줄: 기마
        inv.setItem(CSLOT_BOW_CAV,   icon(Material.SADDLE,           "§a활 기마"));
        inv.setItem(CSLOT_SWORD_CAV, icon(Material.DIAMOND_HORSE_ARMOR,"§a검 기마"));
        p.openInventory(inv);
    }
    private ItemStack icon(Material mat, String name, String... lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(name);
        if (lore != null && lore.length > 0) m.setLore(Arrays.asList(lore));
        it.setItemMeta(m);
        return it;
    }

    @EventHandler
    public void onInvClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        String title = e.getView().getTitle();
        if (title == null) return;

        if (MENU_TITLE_ROOT.equals(title)) {
            e.setCancelled(true);
            ItemStack clicked = e.getCurrentItem();
            if (clicked == null || !clicked.hasItemMeta()) return;
            int slot = e.getRawSlot();
            if (slot == SLOT_START) {
                p.closeInventory();
                startJoinPhase(p); // 시작자 즉시 참가/병과창 자동 오픈 포함
            } else if (slot == SLOT_CANCEL) {
                p.closeInventory();
            }
            return;
        }

        if (MENU_TITLE_ADMIN.equals(title)) {
            e.setCancelled(true);
            if (current == null || current.phase != Phase.COLLECTING) { p.closeInventory(); return; }
            if (!p.getUniqueId().equals(current.initiator)) {
                p.sendMessage("§c개설자만 취소할 수 있습니다."); p.closeInventory(); return;
            }
            if (e.getRawSlot() == ASLOT_FORCE_CANCEL) {
                p.closeInventory();
                cancelRecruitment("§c개설자가 모집을 취소했습니다.");
            }
            return;
        }

        if (MENU_TITLE_CLASS.equals(title)) {
            e.setCancelled(true);
            if (current == null || current.phase != Phase.COLLECTING) { p.closeInventory(); return; }
            if (!current.accepted.contains(p.getUniqueId())) {
                p.sendMessage("§c참가자만 병과를 선택할 수 있습니다."); p.closeInventory(); return;
            }
            ClassType picked = null;
            switch (e.getRawSlot()) {
                case CSLOT_BOW_INF   -> picked = ClassType.BOW_INF;
                case CSLOT_SWORD_INF -> picked = ClassType.SWORD_INF;
                case CSLOT_LANCE_INF -> picked = ClassType.LANCE_INF;
                case CSLOT_BOW_CAV   -> picked = ClassType.BOW_CAV;
                case CSLOT_SWORD_CAV -> picked = ClassType.SWORD_CAV;
            }
            if (picked == null) return;
            current.chosenClass.put(p.getUniqueId(), picked);
            giveLoadout(p, picked);
            p.closeInventory();
            p.sendMessage("§a병과가 §f" + classDisplay(picked) + "§a 로 설정되었습니다.");
        }
    }

    private String classDisplay(ClassType t) {
        return switch (t) {
            case BOW_INF   -> "활 보병";
            case SWORD_INF -> "검 보병";
            case LANCE_INF -> "창 보병";
            case BOW_CAV   -> "활 기마";
            case SWORD_CAV -> "검 기마";
        };
    }

    /* -------------------------------------------------
     * 모집/배치/시작 + 방송/커맨드
     * ------------------------------------------------- */
    private void startJoinPhase(Player opener) {
        current = new Conquest();
        current.phase = Phase.COLLECTING;
        current.initiator = opener.getUniqueId();
        current.startedAtMs = System.currentTimeMillis();

        broadcastJoinMessage(opener);

        // 시작자는 즉시 참가 처리 + 준비장소 TP + 병과창 자동 오픈
        onAcceptInternal(opener, true);

        current.joinTaskId = getServer().getScheduler().runTaskLater(this, () -> {
            if (current == null || current.phase != Phase.COLLECTING) return;
            doTeamAssignOrCancel();
        }, JOIN_WINDOW_TICKS).getTaskId();
    }

    private void broadcastJoinMessage(Player opener) {
        TextComponent prefix = Component.text("[팀 점령전] ", NamedTextColor.GOLD);
        Component q = Component.text(opener.getName(), NamedTextColor.GREEN)
                .append(Component.text(" 님이 팀 점령전을 시작했습니다. 참가하시겠습니까? ", NamedTextColor.WHITE));
        Component accept = Component.text("[수락] ", NamedTextColor.GREEN)
                .clickEvent(ClickEvent.runCommand("/tcap yes"));
        Component decline = Component.text("[거절]", NamedTextColor.RED)
                .clickEvent(ClickEvent.runCommand("/tcap no"));
        Component line = prefix.append(q).append(accept).append(Component.space()).append(decline);
        for (Player pl : Bukkit.getOnlinePlayers()) pl.sendMessage(line);
        getLogger().info("TeamConquest: 모집 방송 발송됨");
    }

    /* /tcap yes|no|menu|cancel (plugin.yml 없이 전처리로 처리) */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent e) {
        String msg = e.getMessage().trim().toLowerCase(Locale.ROOT);
        if (!msg.startsWith("/tcap")) return;
        e.setCancelled(true);

        String[] parts = msg.split("\\s+");

        if (parts.length >= 2 && "menu".equals(parts[1])) {
            if (current != null && current.phase != Phase.IDLE) {
                e.getPlayer().sendMessage("§c현재 다른 팀 점령전이 준비/진행 중입니다.");
                return;
            }
            openRootMenu(e.getPlayer());
            return;
        }
        if (parts.length >= 2 && "cancel".equals(parts[1])) {
            if (current == null || current.phase != Phase.COLLECTING) {
                e.getPlayer().sendMessage("§c취소할 모집이 없습니다."); return;
            }
            if (!e.getPlayer().getUniqueId().equals(current.initiator)) {
                e.getPlayer().sendMessage("§c개설자만 모집을 취소할 수 있습니다."); return;
            }
            cancelRecruitment("§c개설자가 모집을 취소했습니다.");
            return;
        }
        if (parts.length >= 2 && ("yes".equals(parts[1]) || "no".equals(parts[1]))) {
            if (current == null || current.phase != Phase.COLLECTING) {
                e.getPlayer().sendMessage("§c지금은 참가를 받을 수 없습니다."); return;
            }
            if ("yes".equals(parts[1])) onAcceptInternal(e.getPlayer(), false);
            else                       onDecline(e.getPlayer());
            return;
        }
        e.getPlayer().sendMessage("§c/tcap yes | /tcap no | /tcap menu | /tcap cancel");
    }

    private void onAcceptInternal(Player p, boolean openerAuto) {
        if (current.accepted.contains(p.getUniqueId()) && !openerAuto) {
            p.sendMessage("§7이미 참가에 동의했습니다."); return;
        }
        current.accepted.add(p.getUniqueId());
        current.declined.remove(p.getUniqueId());
        current.prevBoards.put(p.getUniqueId(), p.getScoreboard());

        // 준비 장소: 월드 스폰 근처
        Location prep = p.getWorld().getSpawnLocation().clone().add(0, 1, 0);
        p.teleport(prep);

        // 병과 선택창 자동 오픈
        getServer().getScheduler().runTask(this, () -> openClassMenu(p));
        if (!openerAuto) p.sendMessage("§a팀 점령전에 참가하였습니다. 병과를 선택하세요!");
    }

    private void onDecline(Player p) {
        if (current.declined.contains(p.getUniqueId())) { p.sendMessage("§7이미 거절했습니다."); return; }
        current.declined.add(p.getUniqueId());
        current.accepted.remove(p.getUniqueId());
        p.sendMessage("§c참가를 거절했습니다.");
    }

    private void doTeamAssignOrCancel() {
        // 온라인인 수락자만
        List<Player> participants = current.accepted.stream()
                .map(Bukkit::getPlayer).filter(Objects::nonNull).filter(Player::isOnline).collect(Collectors.toList());

        if (participants.size() < 4) {
            cancelRecruitment("§c참가 인원이 부족하여 팀 점령전이 취소되었습니다. (최소 4명 필요)");
            return;
        }

        // 병과 미선택자 기본값(검 보병)
        for (Player p : participants) current.chosenClass.putIfAbsent(p.getUniqueId(), ClassType.SWORD_INF);

        // 랜덤 셔플 후 반으로 분리
        Collections.shuffle(participants);
        int mid = participants.size() / 2;
        List<Player> A = new ArrayList<>(participants.subList(0, mid));
        List<Player> B = new ArrayList<>(participants.subList(mid, participants.size()));

        current.teamA.clear(); current.teamB.clear();
        A.forEach(pl -> current.teamA.add(pl.getUniqueId()));
        B.forEach(pl -> current.teamB.add(pl.getUniqueId()));

        // 관점별 스코어보드(아군 파랑, 적군 흰색) — 경기 중 점수는 표시하지 않음
        for (Player viewer : participants) applyPerPlayerScoreboard(viewer, A, B, true);

        // 팀별 이동(스폰 기준 좌/우 5블럭, 기마는 말째 이동) — 배치 단계
        for (Player pl : A) teleportWithMount(pl, +5);
        for (Player pl : B) teleportWithMount(pl, -5);

        // 참가자 스냅샷 고정
        current.participants.clear();
        current.participants.addAll(participants.stream().map(Player::getUniqueId).toList());

        // 시작 카운트다운 후 본게임 시작
        startCountdownThenBegin(A, B, participants);
    }

    /** 모집 단계 강제 취소(개설자/자동부족) 공용 처리 */
    private void cancelRecruitment(String reasonMsg) {
        if (current == null) return;

        // 온라인 참가자 전원 처리
        List<Player> participants = current.accepted.stream()
                .map(Bukkit::getPlayer).filter(Objects::nonNull).filter(Player::isOnline).toList();

        for (Player p : participants) {
            // 1) 미니게임 장비/효과 전부 제거
            p.getActivePotionEffects().forEach(pe -> p.removePotionEffect(pe.getType()));
            p.getInventory().clear();
            p.getInventory().setArmorContents(null);
            p.getInventory().setItemInOffHand(null);
            p.updateInventory();

            // 2) 플러그인이 소환한 말에 타고 있으면 바로 제거
            Entity veh = p.getVehicle();
            if (veh instanceof Horse h && spawnedHorses.contains(h.getUniqueId())) {
                h.remove();
                spawnedHorses.remove(h.getUniqueId());
            }

            // 3) 스폰 복귀 + 시작아이템 회수 + 안내
            p.teleport(p.getWorld().getSpawnLocation());
            removeStartItem(p);
            p.sendMessage(reasonMsg);
        }

        // 말/스케줄/보드 등 나머지 정리는 기존 로직으로
        resetEvent();
    }

    /* -------------------------------------------------
     * 장비/말/이동 유틸
     * ------------------------------------------------- */
    private void giveLoadout(Player p, ClassType t) {
        // 인벤 초기화
        p.getInventory().clear();
        p.getInventory().setArmorContents(null);
        p.getInventory().setItemInOffHand(null);

        // 공통 방어구(보호 II)
        p.getInventory().setHelmet(enchant(new ItemStack(Material.DIAMOND_HELMET), Enchantment.PROTECTION_ENVIRONMENTAL, 2));
        p.getInventory().setChestplate(enchant(new ItemStack(Material.DIAMOND_CHESTPLATE), Enchantment.PROTECTION_ENVIRONMENTAL, 2));
        p.getInventory().setLeggings(enchant(new ItemStack(Material.DIAMOND_LEGGINGS), Enchantment.PROTECTION_ENVIRONMENTAL, 2));
        p.getInventory().setBoots(enchant(new ItemStack(Material.DIAMOND_BOOTS), Enchantment.PROTECTION_ENVIRONMENTAL, 2));

        // 공통 소모품
        p.getInventory().setItem(8, new ItemStack(Material.GOLDEN_APPLE, 8));
        p.getInventory().setItem(7, new ItemStack(Material.COOKED_BEEF, 32));

        switch (t) {
            case SWORD_INF -> {
                p.getInventory().setItem(0, enchant(new ItemStack(Material.DIAMOND_SWORD), Enchantment.DAMAGE_ALL, 2)); // 날카 II
                p.getInventory().setItem(1, enchant(new ItemStack(Material.DIAMOND_AXE), Enchantment.DAMAGE_UNDEAD, 1)); // 강타 I
                p.getInventory().setItemInOffHand(new ItemStack(Material.SHIELD)); // 방패
            }
            case SWORD_CAV -> {
                p.getInventory().setItem(0, enchant(new ItemStack(Material.DIAMOND_SWORD), Enchantment.DAMAGE_ALL, 2));
                p.getInventory().setItem(1, enchant(new ItemStack(Material.DIAMOND_AXE), Enchantment.DAMAGE_UNDEAD, 1));
                p.getInventory().setItem(2, new ItemStack(Material.HAY_BLOCK, 64)); // 밀짚
                p.getInventory().setItemInOffHand(new ItemStack(Material.SHIELD));
                ensureMountedOnHorse(p);
            }
            case LANCE_INF -> {
                // 창 = 네더라이트 도끼(날카 III + 강타 III)
                ItemStack spear = new ItemStack(Material.NETHERITE_AXE);
                spear.addUnsafeEnchantment(Enchantment.DAMAGE_ALL, 3);
                spear.addUnsafeEnchantment(Enchantment.DAMAGE_UNDEAD, 3);
                p.getInventory().setItem(0, spear);
                p.getInventory().setItemInOffHand(new ItemStack(Material.SHIELD));
            }
            case BOW_INF -> {
                ItemStack bow = enchant(new ItemStack(Material.BOW), Enchantment.ARROW_DAMAGE, 3); // 힘 III
                bow.addEnchantment(Enchantment.ARROW_INFINITE, 1);
                p.getInventory().setItem(0, bow);
                p.getInventory().setItem(18, new ItemStack(Material.ARROW, 1)); // 내부 슬롯
            }
            case BOW_CAV -> {
                ItemStack bow = enchant(new ItemStack(Material.BOW), Enchantment.ARROW_DAMAGE, 3);
                bow.addEnchantment(Enchantment.ARROW_INFINITE, 1);
                p.getInventory().setItem(0, bow);
                p.getInventory().setItem(1, new ItemStack(Material.HAY_BLOCK, 64)); // 밀짚
                p.getInventory().setItem(18, new ItemStack(Material.ARROW, 1));
                ensureMountedOnHorse(p);
            }
        }
        p.updateInventory();
    }

    private void ensureMountedOnHorse(Player player) {
        if (player.getVehicle() instanceof Horse) return;
        Horse h = spawnHorseFor(player);
        if (h != null) {
            h.addPassenger(player);
            spawnedHorses.add(h.getUniqueId());
        }
    }

    private void teleportWithMount(Player p, int dx) {
        Location base = p.getWorld().getSpawnLocation().clone().add(dx, 0, 0);
        int y = p.getWorld().getHighestBlockYAt(base);
        Location safe = new Location(base.getWorld(), base.getX(), y + 1, base.getZ());
        if (p.getVehicle() instanceof Horse h && !h.isDead()) {
            h.teleport(safe);
            if (!h.getPassengers().contains(p)) h.addPassenger(p); // 탑승 유지 보정
        } else {
            p.teleport(safe);
        }
    }

    private ItemStack enchant(ItemStack item, Enchantment ench, int level) {
        ItemMeta meta = item.getItemMeta();
        meta.addEnchant(ench, level, true);
        item.setItemMeta(meta);
        return item;
    }

    private Horse spawnHorseFor(Player player) {
        Horse horse = (Horse) player.getWorld().spawnEntity(player.getLocation(), EntityType.HORSE);
        try { horse.setOwner(player); } catch (Throwable ignored) {}
        horse.setTamed(true);
        horse.setAdult();
        horse.setMaxHealth(30.0);
        horse.setHealth(30.0);
        AttributeInstance speed = horse.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED);
        if (speed != null) speed.setBaseValue(0.30);
        horse.setJumpStrength(1.0);
        ItemStack armor = new ItemStack(Material.DIAMOND_HORSE_ARMOR);
        ItemMeta meta = armor.getItemMeta();
        meta.addEnchant(Enchantment.PROTECTION_ENVIRONMENTAL, 1, true); // 보호 I
        armor.setItemMeta(meta);
        horse.getInventory().setArmor(armor);
        horse.getInventory().setSaddle(new ItemStack(Material.SADDLE));
        return horse;
    }

    /* 개인 스코어보드: 아군 파랑, 적군 흰색 (경기 중 점수 사이드바는 없음) */
    private void applyPerPlayerScoreboard(Player viewer, List<Player> A, List<Player> B, boolean createObjective) {
        ScoreboardManager sm = Bukkit.getScoreboardManager();
        Scoreboard board = sm.getNewScoreboard();

        boolean viewerInA = A.stream().anyMatch(p -> p.getUniqueId().equals(viewer.getUniqueId()));
        List<Player> allies = viewerInA ? A : B;
        List<Player> enemies = viewerInA ? B : A;

        Team ally = board.registerNewTeam("ally");
        ally.setColor(org.bukkit.ChatColor.BLUE);
        ally.setCanSeeFriendlyInvisibles(true);

        Team enemy = board.registerNewTeam("enemy");
        enemy.setColor(org.bukkit.ChatColor.WHITE);

        for (Player p : allies) ally.addEntry(p.getName());
        for (Player p : enemies) enemy.addEntry(p.getName());

        viewer.setScoreboard(board);
        if (current != null) current.liveBoards.put(viewer.getUniqueId(), board);
    }

    /* -------------------------------------------------
     * 시작 카운트다운 & 본게임 시작/종료
     * ------------------------------------------------- */
    private void startCountdownThenBegin(List<Player> A, List<Player> B, List<Player> participants) {
        // 임시 전장 설정(후에 실제 신호기/팀 스폰 설정으로 교체)
        World w = participants.get(0).getWorld();
        current.capCenter = w.getSpawnLocation().clone(); // TODO: 실제 신호기 좌표로 치환
        current.spawnA    = toGround(current.capCenter.clone().add(-12, 0, 0));
        current.spawnB    = toGround(current.capCenter.clone().add(+12, 0, 0));

        // 모두 팀 스폰으로 정렬 + 시작 아이템 제거 + 클래스 장비 재지급
        for (Player p : participants) {
            Location dst = current.teamA.contains(p.getUniqueId()) ? current.spawnA : current.spawnB;
            // 말째 이동 보장
            if (p.getVehicle() instanceof Horse h && !h.isDead()) h.teleport(dst);
            p.teleport(dst);
            removeStartItem(p);
            giveLoadout(p, current.chosenClass.getOrDefault(p.getUniqueId(), ClassType.SWORD_INF));
        }

        // 카운트다운
        final int[] left = {COUNTDOWN_SECONDS};
        current.countdownTaskId = getServer().getScheduler().runTaskTimer(this, () -> {
            if (left[0] <= 0) {
                // 시작!
                for (Player p : participants) p.sendTitle("§a시작!", "§7점령구역을 차지하세요!", 0, 30, 0);
                getServer().getScheduler().runTask(this, this::beginGameLoop);
                getServer().getScheduler().cancelTask(current.countdownTaskId);
                current.countdownTaskId = -1;
                return;
            }
            for (Player p : participants) p.sendTitle("§e" + left[0], "§7곧 시작", 0, 20, 0);
            left[0]--;
        }, 0L, 20L).getTaskId();
    }

    private void beginGameLoop() {
        if (current == null) return;
        current.phase = Phase.STARTED;

        // 타이머 바 생성
        if (current.timerBar == null) {
            current.timerBar = Bukkit.createBossBar("§6점령전 §f15:00", BarColor.YELLOW, BarStyle.SEGMENTED_12);
            for (UUID uid : current.participants) {
                Player p = Bukkit.getPlayer(uid);
                if (p != null && p.isOnline()) current.timerBar.addPlayer(p);
            }
            current.timerBar.setVisible(true);
            current.gameEndAtMs = System.currentTimeMillis() + GAME_DURATION_MS;
            current.timerBar.setProgress(1.0);
        }

        // 캡쳐 루프(1초) + 타이머 갱신
        current.capTaskId = getServer().getScheduler().runTaskTimer(this, () -> {
            tickCapture();
            updateTimerBar(); // 남은 시간/프로그레스
        }, 20L, 20L).getTaskId();

        // 경기 종료 타이머(15분)
        current.endTaskId = getServer().getScheduler().runTaskLater(this, this::finishGame, GAME_DURATION_TICKS).getTaskId();
    }

    private void updateTimerBar() {
        if (current == null || current.timerBar == null) return;
        long leftMs = Math.max(0, current.gameEndAtMs - System.currentTimeMillis());
        double progress = Math.min(1.0, Math.max(0.0, (double) leftMs / GAME_DURATION_MS));
        current.timerBar.setProgress(progress);
        current.timerBar.setTitle("§6점령전 §f" + formatTime(leftMs));
    }
    private String formatTime(long ms) {
        long total = ms / 1000;
        long m = total / 60;
        long s = total % 60;
        return String.format("%02d:%02d", m, s);
    }

    private void finishGame() {
        if (current == null) return;

        String result;
        if (current.scoreA > current.scoreB) result = "§9청팀 승리! §7(" + current.scoreA + " : " + current.scoreB + ")";
        else if (current.scoreB > current.scoreA) result = "§f백팀 승리! §7(" + current.scoreA + " : " + current.scoreB + ")";
        else result = "§e무승부 §7(" + current.scoreA + " : " + current.scoreB + ")";

        for (UUID uid : current.participants) {
            Player p = Bukkit.getPlayer(uid);
            if (p != null && p.isOnline()) {
                p.sendMessage("§6[팀 점령전] §f15분 종료! 결과: " + result);
            }
        }

        // 타이머 바 숨김/해제
        if (current.timerBar != null) {
            current.timerBar.removeAll();
            current.timerBar.setVisible(false);
        }

        // 즉사 → 드랍 방지는 onPlayerDeath에서 처리(게임 중 플래그 덕분)
        for (UUID uid : current.participants) {
            Player p = Bukkit.getPlayer(uid);
            if (p != null && p.isOnline()) {
                endedTeleport.add(uid); // 리스폰 시 스폰으로 보낼 플래그
                p.setHealth(0.0);
            }
        }

        // 리스폰 이벤트가 먼저 돌도록 한 뒤 리셋
        getServer().getScheduler().runTaskLater(this, this::resetEvent, 2L);
    }

    /* -------------------------------------------------
     * 캡쳐 로직(점유 20초 → 1점, 동시 진입 시 정지)
     * ------------------------------------------------- */
    // 캡쳐 로직(점유 20초 → 1점, 동시 진입 = 진행도 일시정지, 무인 = 진행도 리셋)
    private void tickCapture() {
        if (current == null || current.phase != Phase.STARTED || current.capCenter == null) return;

        List<Player> aAliveInCap = aliveTeam(current.teamA).stream().filter(this::isInCap).toList();
        List<Player> bAliveInCap = aliveTeam(current.teamB).stream().filter(this::isInCap).toList();

        boolean aPresent = !aAliveInCap.isEmpty();
        boolean bPresent = !bAliveInCap.isEmpty();

        if (aPresent && !bPresent) {
            // A팀만 점유 → A 진행
            current.capTickA++;
            current.capTickB = 0;
            if (current.capTickA >= SECONDS_PER_POINT) {
                current.capTickA = 0;
                current.scoreA++;
            }
        } else if (bPresent && !aPresent) {
            // B팀만 점유 → B 진행
            current.capTickB++;
            current.capTickA = 0;
            if (current.capTickB >= SECONDS_PER_POINT) {
                current.capTickB = 0;
                current.scoreB++;
            }
        } else if (aPresent && bPresent) {
            // ★ 동시 진입 → 진행도 '그대로 유지'(일시정지)
            // 아무 것도 하지 않음
        } else {
            // ★ 아무도 없음(무인) → 진행도 리셋
            current.capTickA = 0;
            current.capTickB = 0;
        }
    }


    private boolean isInCap(Player p) {
        if (current.capCenter == null) return false;
        Location l = p.getLocation();
        int bx = l.getBlockX();
        int bz = l.getBlockZ();
        int cx = current.capCenter.getBlockX();
        int cz = current.capCenter.getBlockZ();
        return (bx >= cx - 2 && bx <= cx + 1) && (bz >= cz - 2 && bz <= cz + 1);
    }

    private Location toGround(Location base) {
        World w = base.getWorld();
        int y = w.getHighestBlockYAt(base);
        return new Location(w, base.getX(), y + 1, base.getZ());
    }

    /* -------------------------------------------------
     * 사망/부활 (개인 20초, 팀 전멸 시 마지막 사망 기준 20초 동시 부활)
     * ------------------------------------------------- */
    @EventHandler(ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (current == null || current.phase != Phase.STARTED || !isParticipant(p.getUniqueId())) return;

        boolean endKill = endedTeleport.contains(p.getUniqueId());
        e.setKeepInventory(!endKill);  // 종료킬이면 false
        e.getDrops().clear();          // 드롭 비움(종료킬일 때도 땅에 안 남게)
        e.setDroppedExp(0);

        if (!endKill) {                // 일반 경기 도중 사망만 ‘죽음 집계’
            current.dead.add(p.getUniqueId());
            current.deathAt.put(p.getUniqueId(), System.currentTimeMillis());
        }
    }

    private void handleRespawnSchedulingAfterDeath(Player pJustRespawned) {
        if (current == null || current.phase != Phase.STARTED) return;

        boolean isA = current.teamA.contains(pJustRespawned.getUniqueId());
        Set<UUID> myTeam = isA ? current.teamA : current.teamB;

        boolean teamAliveExists = myTeam.stream().anyMatch(uid -> {
            Player pp = Bukkit.getPlayer(uid);
            return pp != null && pp.isOnline() && !current.dead.contains(uid);
        });

        // 팀 전멸 → 마지막 사망 기준 20초 뒤 동시 부활
        if (!teamAliveExists) {
            // 개인 예약 취소
            for (UUID uid : myTeam) {
                Integer tid = current.indivRespawnTaskId.remove(uid);
                if (tid != null) getServer().getScheduler().cancelTask(tid);
            }
            // 팀 예약 재설정
            if (isA) {
                if (current.teamARespawnTaskId != null)
                    getServer().getScheduler().cancelTask(current.teamARespawnTaskId);
                current.teamARespawnTaskId = getServer().getScheduler().runTaskLater(this,
                        () -> respawnTeam(myTeam, current.spawnA), 20L * RESPAWN_SECONDS).getTaskId();
            } else {
                if (current.teamBRespawnTaskId != null)
                    getServer().getScheduler().cancelTask(current.teamBRespawnTaskId);
                current.teamBRespawnTaskId = getServer().getScheduler().runTaskLater(this,
                        () -> respawnTeam(myTeam, current.spawnB), 20L * RESPAWN_SECONDS).getTaskId();
            }
        } else {
            // 개인 20초 타이머
            UUID uid = pJustRespawned.getUniqueId();
            if (current.indivRespawnTaskId.containsKey(uid)) return; // 이미 예약됨
            int tid = getServer().getScheduler().runTaskLater(this, () -> {
                if (isA) completeRespawn(uid, current.spawnA);
                else     completeRespawn(uid, current.spawnB);
            }, 20L * RESPAWN_SECONDS).getTaskId();
            current.indivRespawnTaskId.put(uid, tid);
        }
    }

    private void respawnTeam(Set<UUID> team, Location spawn) {
        for (UUID uid : team) completeRespawn(uid, spawn);
        // 팀 예약 클리어
        if (team == current.teamA) current.teamARespawnTaskId = null;
        else                       current.teamBRespawnTaskId = null;
    }

    private void completeRespawn(UUID uid, Location spawn) {
        if (current == null || current.phase != Phase.STARTED) return;
        Player p = Bukkit.getPlayer(uid);
        if (p == null || !p.isOnline()) return;

        // 생존 처리
        current.dead.remove(uid);
        current.indivRespawnTaskId.remove(uid);

        // 실제 부활: 스펙테이터 → 서바이벌, 체력/허기 회복, 텔레포트, 장비 복구
        p.setGameMode(GameMode.SURVIVAL);
        p.setHealth(Math.min(p.getMaxHealth(), 20.0));
        p.setFoodLevel(20);
        p.setSaturation(10);

        // 안전 텔레포트
        Location safe = toGround(spawn.clone());
        // 탑승 중 말은 보통 없음(죽은 상태)이나 방어 코드
        Entity veh = p.getVehicle();
        if (veh instanceof Horse h && !h.isDead()) h.teleport(safe);
        p.teleport(safe);

        // 클래스 장비 재지급 + 기마는 말 보장
        ClassType ct = current.chosenClass.getOrDefault(uid, ClassType.SWORD_INF);
        giveLoadout(p, ct);
    }

    private boolean isParticipant(UUID uid) {
        return current != null && current.participants.contains(uid);
    }
    private List<Player> aliveTeam(Set<UUID> team) {
        return team.stream().map(Bukkit::getPlayer)
                .filter(Objects::nonNull).filter(Player::isOnline)
                .filter(p -> !current.dead.contains(p.getUniqueId()))
                .filter(p -> p.getGameMode() == GameMode.SURVIVAL)
                .toList();
    }

    /* -------------------------------------------------
     * 검 기마 탑승 중 +10% 데미지
     * ------------------------------------------------- */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.NORMAL)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        if (current == null || current.phase != Phase.STARTED || !isParticipant(p.getUniqueId())) return;

        ClassType ct = current.chosenClass.getOrDefault(p.getUniqueId(), ClassType.SWORD_INF);
        if (ct == ClassType.SWORD_CAV && p.getVehicle() instanceof Horse) {
            e.setDamage(e.getDamage() * 1.10); // +10%
        }
    }

    /* -------------------------------------------------
     * 말 드랍 차단
     * ------------------------------------------------- */
    @EventHandler(ignoreCancelled = true)
    public void onHorseDeath(EntityDeathEvent e) {
        Entity ent = e.getEntity();
        if (!(ent instanceof Horse horse)) return;
        if (!spawnedHorses.contains(horse.getUniqueId())) return;
        e.getDrops().clear();
        e.setDroppedExp(0);
    }

    /* -------------------------------------------------
     * 리셋
     * ------------------------------------------------- */
    private void resetEvent() {
        if (current != null) {
            // 스케줄 취소
            if (current.joinTaskId != -1) getServer().getScheduler().cancelTask(current.joinTaskId);
            if (current.capTaskId  != -1) getServer().getScheduler().cancelTask(current.capTaskId);
            if (current.endTaskId  != -1) getServer().getScheduler().cancelTask(current.endTaskId);
            if (current.countdownTaskId != -1) getServer().getScheduler().cancelTask(current.countdownTaskId);
            if (current.teamARespawnTaskId != null) getServer().getScheduler().cancelTask(current.teamARespawnTaskId);
            if (current.teamBRespawnTaskId != null) getServer().getScheduler().cancelTask(current.teamBRespawnTaskId);
            for (Integer tid : current.indivRespawnTaskId.values())
                getServer().getScheduler().cancelTask(tid);
            current.indivRespawnTaskId.clear();

            // 타이머 바 정리
            if (current.timerBar != null) {
                current.timerBar.removeAll();
                current.timerBar = null;
            }

            // 스코어보드 원복
            for (Map.Entry<UUID, Scoreboard> ent : current.prevBoards.entrySet()) {
                Player p = Bukkit.getPlayer(ent.getKey());
                if (p != null && p.isOnline()) {
                    try { p.setScoreboard(ent.getValue()); } catch (Exception ignored) {}
                    // 게임 모드 복구 보정
                    if (p.getGameMode() == GameMode.SPECTATOR) p.setGameMode(GameMode.SURVIVAL);
                }
            }
        }

        // 생성했던 말 정리
        for (UUID hid : new HashSet<>(spawnedHorses)) {
            for (World w : Bukkit.getWorlds()) {
                Entity ent = w.getEntity(hid);
                if (ent instanceof Horse h && !h.isDead()) h.remove();
            }
            spawnedHorses.remove(hid);
        }

        endedTeleport.clear();
        current = null;
    }

    /* === helper: 팀 이름 === */
    private String teamNameOf(UUID uid) {
        if (current.teamA.contains(uid)) return TEAM_A_LABEL;
        if (current.teamB.contains(uid)) return TEAM_B_LABEL;
        return "§7무소속";
    }
}
