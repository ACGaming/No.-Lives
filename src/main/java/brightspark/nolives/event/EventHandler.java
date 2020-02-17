package brightspark.nolives.event;
import java.util.Arrays;
import brightspark.nolives.NLConfig;
import brightspark.nolives.NoLives;
import brightspark.nolives.livesData.PlayerLives;
import brightspark.nolives.livesData.PlayerLivesWorldData;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.SoundEvents;
import net.minecraft.init.MobEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.server.management.UserListBansEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiGameOver;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameType;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.potion.PotionEffect;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerRespawnEvent;

import java.util.UUID;

@Mod.EventBusSubscriber
public class EventHandler {
	private static boolean deleteWorld = false;
	
	public static boolean shouldDeleteWorld() {
		if (deleteWorld) {
			deleteWorld = false;
			return true;
		} else
			return false;
	}

	private static boolean isHardcore(World world) {
		return world.getWorldInfo().isHardcoreModeEnabled();
	}

	@SubscribeEvent(priority = EventPriority.LOW)
	public static void onPlayerDeathSubLives(LivingDeathEvent event) {
		if (!NLConfig.enabled || !(event.getEntityLiving() instanceof EntityPlayerMP) || isHardcore(event.getEntityLiving().world))
			return;
		EntityPlayerMP player = (EntityPlayerMP) event.getEntityLiving();

		// If Sync mod installed and player is syncing to a shell, then don't lose a life
		if (player.getEntityData().getBoolean("isDeathSyncing"))
			return;
		
		if (!player.isSpectator()){
			LifeChangeEvent.LifeLossEvent lifeLossEvent = new LifeChangeEvent.LifeLossEvent(player, 1);
			if (!MinecraftForge.EVENT_BUS.post(lifeLossEvent)) {
				int livesToLose = lifeLossEvent.getLivesToLose();
				if (livesToLose > 0) {
					PlayerLivesWorldData data = PlayerLivesWorldData.get(player.world);
					if (data == null) return;
					data.setLastRegenToCurrentTime(player);
					int livesLeft = data.subLives(player.getUniqueID(), livesToLose);
					String message = NoLives.getRandomDeathMessage();
					if (message != null) player.sendMessage(new TextComponentString(String.format(message, livesLeft)));
				}
			}
		}
		if (NLConfig.SpawnAtDawn) {
			if (player.isSpectator() == false) {
				player.inventory.dropAllItems();
				player.setGameType(GameType.SPECTATOR);
				player.setHealth((float)10.0);
				player.addPotionEffect(new PotionEffect(MobEffects.NIGHT_VISION, 1000000, 0));
				event.setCanceled(true);
			}
		}
	}
	
	@SubscribeEvent(priority = EventPriority.LOWEST)
	public static void onPlayerDeathKick(LivingDeathEvent event) {
		//Kick and ban the player if lives are 0 after a short delay (to allow for dropping of items and other things)
		if (!NLConfig.enabled || !(event.getEntityLiving() instanceof EntityPlayer) || isHardcore(event.getEntityLiving().world))
			return;
		EntityPlayer player = (EntityPlayer) event.getEntityLiving();
		if (PlayerLivesWorldData.get(player.world).getLives(player.getUniqueID()) > 0)
			return;
		MinecraftServer server = player.getServer();
		if (server == null)
			return;

		//Message all players
		String message = NoLives.getRandomOutOfLivesMessage();
		if (message != null)
			server.getPlayerList().getPlayers().forEach((p) -> p.sendMessage(new TextComponentString(String.format(message, player.getDisplayNameString()))));
		//Play death sound
		player.world.playSound(null, player.getPosition(), SoundEvents.ENTITY_PLAYER_DEATH, SoundCategory.PLAYERS, 1f, 0.5f);

		if (!NLConfig.banOnOutOfLives)
			player.setGameType(GameType.SPECTATOR);
		else if (server.isDedicatedServer()) {
			//Multiplayer server - kick player
			kickBanPlayer((EntityPlayerMP) player, server);
		} else {
			if (player.getName().equals(server.getServerOwner())) {
				if (server.isSinglePlayer()) {
					//Single player - delete world
					deleteWorld = true;
					server.initiateShutdown();
				} else {
					//LAN server host. Can't kick/ban them! Must put them into spectator mode.
					player.setGameType(GameType.SPECTATOR);
				}
			} else if (player instanceof EntityPlayerMP) {
				//Other player - kick player
				kickBanPlayer((EntityPlayerMP) player, server);
			}
		}
	}

	private static void kickBanPlayer(EntityPlayerMP player, MinecraftServer server) {
		UserListBansEntry banEntry = new UserListBansEntry(player.getGameProfile(), null, NoLives.MOD_NAME, null, "You ran out of lives!");
		server.getPlayerList().getBannedPlayers().addEntry(banEntry);
		player.connection.disconnect(new TextComponentTranslation(NoLives.MOD_ID + ".message.kick"));
	}
	
	private static boolean[] isDeadState = {};
	private static boolean PendingRespawns = false;
	private static MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
	private static WorldServer overworld = server.getWorld(0);
	private static void addDeadState(boolean state){
		isDeadState = Arrays.copyOf(isDeadState, isDeadState.length + 1);
		isDeadState[isDeadState.length - 1] = state;
	}
	
	private static void clearDeadStates(){
		isDeadState = new boolean[]{};
	}
	
	private static boolean AllDeadCheck(){
		if (isDeadState == new boolean[]{}) return false;
		for(boolean b : isDeadState) if(!b) return false;
		return true;
	}
	
	@SubscribeEvent
	public static void onServerTick(TickEvent.ServerTickEvent event) {
		if (!NLConfig.enabled || event.phase != TickEvent.Phase.END) return;
		if (server == null || server.isHardcore()) return;
		long worldTime = overworld.getTotalWorldTime();
		long currentTime = overworld.getWorldTime();
		//Only check once every second
		if (worldTime % 5 == 0 && NLConfig.regenSeconds > 0) {
			PlayerLivesWorldData data = PlayerLivesWorldData.get(overworld);
			if (data == null) return;
			long lastRegenTime = worldTime - (NLConfig.regenSeconds * 20);
			//For each player, give them a life if it's been long enough since their last
			server.getPlayerList().getPlayers().forEach(player -> {
				if (!player.isDead) {
					PlayerLives pl = data.getPlayerLives(player.getUniqueID());
					if (pl.lives > 0 && pl.lives < NLConfig.regenMaxLives && pl.lastRegen <= lastRegenTime) {
						pl.lastRegen = worldTime;
						LifeChangeEvent.LifeGainEvent lifeGainEvent = new LifeChangeEvent.LifeGainEvent(player, 1, LifeChangeEvent.LifeGainEvent.GainType.REGEN);
						if (!MinecraftForge.EVENT_BUS.post(lifeGainEvent) && lifeGainEvent.getLivesToGain() > 0) {
							int gained = lifeGainEvent.getLivesToGain();
							pl.lives += gained;
							NoLives.sendMessageText(player, "regen", gained, NoLives.lifeOrLives(gained), pl.lives, NoLives.lifeOrLives(pl.lives));
						}
						data.markDirty();
					}
				}
			});
		}
		if (NLConfig.SpawnAtDawn){
			//Only do at dawn each day
			if (currentTime % 24000 == 0){
				PlayerLivesWorldData data = PlayerLivesWorldData.get(overworld);
				if (data == null) return;
				server.getPlayerList().getPlayers().forEach(player -> {
					PlayerLives pl = data.getPlayerLives(player.getUniqueID());
					if (pl.lives > 0 && player.isSpectator() == true ){
						player.setGameType(GameType.SURVIVAL);
						player.setHealth((float)0.0);
					}
				});
				PendingRespawns = false;
			}
			
			if (worldTime % 25 == 0) {
				PlayerLivesWorldData data = PlayerLivesWorldData.get(overworld);
				if (data == null) return;
				server.getPlayerList().getPlayers().forEach(player -> {
					PlayerLives pl = data.getPlayerLives(player.getUniqueID());
					if (player.isSpectator() && pl.lives > 0) {
						addDeadState(true);
					}
					else if (!player.isSpectator()) {
						addDeadState(false);
					}
				});
				if(AllDeadCheck() && !PendingRespawns) {
					server.getPlayerList().getPlayers().forEach(player -> {
						player.sendMessage(new TextComponentString("All Players have died! Respawning..."));
					});
					overworld.setWorldTime(23500);
					PendingRespawns = true;
				}
				if (isDeadState != new boolean[]{}) clearDeadStates();
			}
		}
	}
	
//	@SubscribeEvent(priority = EventPriority.HIGHEST)
//	public static void GuiOpenEvent(GuiOpenEvent event){
//		if (event.getGui() instanceof GuiGameOver && NLConfig.SpawnAtDawn){
//			event.setCanceled(true);
//		}
//	}

	@SubscribeEvent
	public static void onClone(PlayerEvent.Clone event) {
		if (event.isWasDeath()) {
			//Just need to reset the last regen time so they don't start regenerating lives as soon as they respawn
			EntityPlayer player = event.getEntityPlayer();
			PlayerLivesWorldData data = PlayerLivesWorldData.get(player.world);
			if (data != null)
				data.setLastRegenToCurrentTime(player);
		}
	}

	@SubscribeEvent
	public static void onLogin(net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerLoggedInEvent event) {
		// Ensure player lives data is created for the player
		EntityPlayer player = event.player;
		PlayerLivesWorldData data = PlayerLivesWorldData.get(player.world);
		if (data != null) {
			UUID uuid = player.getUniqueID();
			int lives = data.getLives(uuid);
			if (data.getAndRemovePendingRevival(uuid) || (NLConfig.reviveOnLogin && lives > 0 && player.isSpectator())) {
				// Change player back to survival
				player.setGameType(GameType.SURVIVAL);
				NoLives.sendMessageText(player, "revive", lives);
			}
		}
	}
}
