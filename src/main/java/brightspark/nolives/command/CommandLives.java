package brightspark.nolives.command;

import brightspark.nolives.NoLives;
import brightspark.nolives.livesData.PlayerLives;
import brightspark.nolives.livesData.PlayerLivesWorldData;
import com.google.common.collect.Lists;
import com.mojang.authlib.GameProfile;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerProfileCache;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class CommandLives extends CommandBase
{
    @Override
    public String getName()
    {
        return "lives";
    }

    @Override
    public String getUsage(ICommandSender sender)
    {
        return "lives [list]\n" +
                "lives <add|sub|set> [playerName] <amount>";
    }

    @Override
    public boolean checkPermission(MinecraftServer server, ICommandSender sender)
    {
        return true;
    }

    private boolean canSenderUseCommand(ICommandSender sender, String commandVariant)
    {
        return commandVariant.equalsIgnoreCase("list") || sender.canUseCommand(2, getName());
    }

    private String genWhitespace(int length)
    {
        StringBuilder sb = new StringBuilder(length);
        for(int i = 0; i < length; i++)
            sb.append(" ");
        return sb.toString();
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException
    {
        if(!(sender instanceof EntityPlayer)) return;
        EntityPlayer player = (EntityPlayer) sender;
        PlayerLivesWorldData livesData = PlayerLivesWorldData.get(player.world);
        if(livesData == null) throw new CommandException("Failed to get Player Lives data from the world");

        if(args.length == 0)
        {
            //Show user how many lives they have left
            int lives = livesData.getLives(player.getUniqueID());
            NoLives.sendMessageText(sender, "lives", lives, NoLives.lifeOrLives(lives));
        }
        else if(canSenderUseCommand(sender, args[0]))
        {
            if(args[0].equalsIgnoreCase("list"))
            {
                ITextComponent text = NoLives.newMessageText("lives.list");
                PlayerProfileCache cache = server.getPlayerProfileCache();
                List<String> playerNames = Lists.newArrayList(cache.getUsernames());
                int longestName = 0;
                for(String name : playerNames)
                    if(name.length() > longestName)
                        longestName = name.length();
                longestName += 5;
                Map<UUID, PlayerLives> allLives = livesData.getAllLives();
                for(Map.Entry<UUID, PlayerLives> entry : allLives.entrySet())
                {
                    UUID uuid = entry.getKey();
                    GameProfile profile = cache.getProfileByUUID(uuid);
                    if(profile == null) continue;
                    String name = profile.getName();
                    String entryLives = String.valueOf(entry.getValue().lives);
                    String whitespace = genWhitespace(longestName - entryLives.length());
                    text.appendText("\n").appendText(name).appendText(whitespace).appendText(entryLives);
                }
                sender.sendMessage(text);
            }
            else if(args.length > 1)
            {
                UUID uuidToChange = player.getUniqueID();
                String playerName = player.getDisplayNameString();
                if(args.length >= 3)
                {
                    //Get UUID for player mentioned
                    GameProfile profile = server.getPlayerProfileCache().getGameProfileForUsername(args[1]);
                    if(profile == null)
                        throw new CommandException("Couldn't find player '" + args[1] + "'");
                    else
                    {
                        uuidToChange = profile.getId();
                        playerName = profile.getName();
                    }
                }
                //Get amount argument
                int amount;
                int argI = args.length >= 3 ? 2 : 1;
                try
                {
                    amount = Integer.parseInt(args[argI]);
                }
                catch(NumberFormatException e)
                {
                    throw new CommandException("Couldn't parse amount '" + args[argI] + "' as a number");
                }

                ITextComponent response;
                int newAmount;
                switch(args[0])
                {
                    case "add": //Add lives
                        newAmount = livesData.addLives(uuidToChange, amount);
                        response = NoLives.newMessageText("lives.add", amount, NoLives.lifeOrLives(amount), playerName, newAmount, NoLives.lifeOrLives(newAmount));
                        break;
                    case "sub": //Sub lives
                        newAmount = livesData.subLives(uuidToChange, amount);
                        response = NoLives.newMessageText("lives.sub", amount, NoLives.lifeOrLives(amount), playerName, newAmount, NoLives.lifeOrLives(newAmount));
                        break;
                    case "set": //Set lives
                        newAmount = livesData.setLives(uuidToChange, amount);
                        response = NoLives.newMessageText("lives.set", newAmount, NoLives.lifeOrLives(newAmount), playerName);
                        break;
                    default:
                        response = new TextComponentString(getUsage(sender));
                }
                sender.sendMessage(response);
            }
        }
        else if(player.world.isRemote)
        {
            //Does not have permission to use command
            TextComponentTranslation message = new TextComponentTranslation("commands.generic.permission");
            message.getStyle().setColor(TextFormatting.RED);
            sender.sendMessage(message);
        }
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos)
    {
        switch(args.length)
        {
            case 1:
                return getListOfStringsMatchingLastWord(args, "list", "add", "sub", "set");
            case 2:
                return getListOfStringsMatchingLastWord(args, server.getPlayerProfileCache().getUsernames());
            default:
                return Collections.emptyList();
        }
    }
}
