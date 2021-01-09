package net.robinfriedli.botify.discord;

import java.awt.Color;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.MessageBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.ChannelType;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageChannel;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.PrivateChannel;
import net.dv8tion.jda.api.entities.TextChannel;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.requests.restaction.MessageAction;
import net.robinfriedli.botify.Botify;
import net.robinfriedli.botify.discord.property.AbstractGuildProperty;
import net.robinfriedli.botify.discord.property.GuildPropertyManager;
import net.robinfriedli.botify.discord.property.properties.ColorSchemeProperty;
import net.robinfriedli.botify.discord.property.properties.TempMessageTimeoutProperty;
import net.robinfriedli.botify.entities.GuildSpecification;
import net.robinfriedli.botify.function.modes.RecursionPreventionMode;
import net.robinfriedli.botify.util.PropertiesLoadingService;
import net.robinfriedli.botify.util.StaticSessionProvider;
import net.robinfriedli.exec.Invoker;
import net.robinfriedli.exec.Mode;
import net.robinfriedli.stringlist.StringList;
import org.hibernate.Session;

public class MessageService {

    private static final Invoker RECURSION_PREVENTION_INVOKER = Invoker.newInstance();
    private static final Mode RECURSION_PREVENTION_MODE = Mode.create().with(new RecursionPreventionMode());

    private final int limit;
    private final Logger logger;

    public MessageService() {
        this(1000);
    }

    public MessageService(int limit) {
        this.limit = limit;
        this.logger = LoggerFactory.getLogger(getClass());
    }

    public CompletableFuture<Message> send(String message, MessageChannel channel) {
        if (message.length() < limit) {
            return sendInternal(channel, message);
        } else {
            List<String> outputParts = separateMessage(message);

            List<CompletableFuture<Message>> futureMessages = outputParts.stream()
                .map(part -> sendInternal(channel, part))
                .collect(Collectors.toList());

            return futureMessages.get(futureMessages.size() - 1);
        }
    }

    public CompletableFuture<Message> send(String message, User user) {
        if (message.length() < limit) {
            return executeMessageAction(user, messageChannel -> messageChannel.sendMessage(message));
        } else {
            List<String> outputParts = separateMessage(message);

            List<CompletableFuture<Message>> futureMessages = outputParts.stream()
                .map(part -> executeMessageAction(user, messageChannel -> messageChannel.sendMessage(part)))
                .collect(Collectors.toList());


            return futureMessages.get(futureMessages.size() - 1);
        }
    }

    public CompletableFuture<Message> send(String message, Guild guild) {
        return executeMessageAction(guild, messageChannel -> messageChannel.sendMessage(message));
    }

    public CompletableFuture<Message> send(MessageEmbed messageEmbed, MessageChannel messageChannel) {
        return sendInternal(messageChannel, messageEmbed);
    }

    public CompletableFuture<Message> send(MessageEmbed messageEmbed, User user) {
        return executeMessageAction(user, messageChannel -> messageChannel.sendMessage(messageEmbed));
    }

    public CompletableFuture<Message> send(MessageEmbed messageEmbed, Guild guild) {
        return executeMessageAction(guild, channel -> channel.sendMessage(messageEmbed));
    }

    public CompletableFuture<Message> send(EmbedBuilder embedBuilder, MessageChannel channel) {
        return send(buildEmbed(embedBuilder), channel);
    }

    public CompletableFuture<Message> send(EmbedBuilder embedBuilder, Guild guild) {
        return send(buildEmbed(embedBuilder), guild);
    }

    public CompletableFuture<Message> sendWithLogo(EmbedBuilder embedBuilder, MessageChannel channel) {
        String baseUri = PropertiesLoadingService.requireProperty("BASE_URI");
        embedBuilder.setThumbnail(baseUri + "/resources-public/img/botify-logo.png");
        return send(buildEmbed(embedBuilder), channel);
    }

    public CompletableFuture<Message> sendWithLogo(EmbedBuilder embedBuilder, Guild guild) {
        String baseUri = PropertiesLoadingService.requireProperty("BASE_URI");
        embedBuilder.setThumbnail(baseUri + "/resources-public/img/botify-logo.png");
        return send(buildEmbed(embedBuilder), guild);
    }

    public CompletableFuture<Message> send(MessageBuilder messageBuilder, InputStream file, String fileName, MessageChannel messageChannel) {
        return executeMessageAction(messageChannel, c -> {
            MessageAction messageAction = c.sendMessage(messageBuilder.build());
            return messageAction.addFile(file, fileName);
        });
    }

    public CompletableFuture<Message> send(MessageBuilder messageBuilder, InputStream file, String fileName, Guild guild) {
        return executeMessageAction(guild, c -> {
            MessageAction messageAction = c.sendMessage(messageBuilder.build());
            return messageAction.addFile(file, fileName);
        });
    }

    public CompletableFuture<Message> sendSuccess(String message, MessageChannel channel) {
        return sendSuccess(message, channel, true);
    }

    public CompletableFuture<Message> sendSuccess(String message, MessageChannel channel, boolean temporary) {
        return sendEmbed("Success", message, Color.GREEN, channel, temporary);
    }

    public CompletableFuture<Message> sendError(String message, MessageChannel channel) {
        return sendError(message, channel, true);
    }

    public CompletableFuture<Message> sendError(String message, MessageChannel channel, boolean temporary) {
        return sendEmbed("Error", message, Color.RED, channel, temporary);
    }

    public CompletableFuture<Message> sendException(String message, MessageChannel channel) {
        return sendException(message, channel, false);
    }

    public CompletableFuture<Message> sendException(String message, MessageChannel channel, boolean temporary) {
        return sendEmbed("Exception", message, Color.RED, channel, temporary);
    }

    public CompletableFuture<Message> sendSuccess(String message, User user) {
        return sendSuccess(message, user, false);
    }

    public CompletableFuture<Message> sendSuccess(String message, User user, boolean temporary) {
        return sendEmbed("Success", message, Color.GREEN, user, temporary);
    }

    public CompletableFuture<Message> sendError(String message, User user) {
        return sendError(message, user, false);
    }

    public CompletableFuture<Message> sendError(String message, User user, boolean temporary) {
        return sendEmbed("Error", message, Color.RED, user, temporary);
    }

    public CompletableFuture<Message> sendException(String message, User user) {
        return sendException(message, user, false);
    }

    public CompletableFuture<Message> sendException(String message, User user, boolean temporary) {
        return sendEmbed("Exception", message, Color.RED, user, temporary);
    }

    public CompletableFuture<Message> sendEmbed(String title, String message, Color color, User user, boolean temporary) {
        return executeForUser(user, privateChannel -> sendEmbed(title, message, color, privateChannel, temporary));
    }

    public CompletableFuture<Message> sendEmbed(String title, String message, Color color, MessageChannel channel, boolean temporary) {
        EmbedBuilder embedBuilder = new EmbedBuilder();
        embedBuilder.setColor(color);
        embedBuilder.setTitle(title);
        embedBuilder.setDescription(message);
        if (temporary) {
            return sendTemporary(embedBuilder.build(), channel);
        } else {
            return send(embedBuilder.build(), channel);
        }
    }

    public CompletableFuture<Message> sendTemporary(MessageEmbed messageEmbed, MessageChannel messageChannel) {
        CompletableFuture<Message> futureMessage = send(messageEmbed, messageChannel);
        futureMessage.thenAccept(message -> new TempMessageDeletionTask(message).schedule());
        return futureMessage;
    }

    public CompletableFuture<Message> sendTemporary(EmbedBuilder embedBuilder, MessageChannel messageChannel) {
        CompletableFuture<Message> futureMessage = send(embedBuilder, messageChannel);
        futureMessage.thenAccept(message -> new TempMessageDeletionTask(message).schedule());
        return futureMessage;
    }

    public CompletableFuture<Message> sendTemporary(String message, MessageChannel messageChannel) {
        CompletableFuture<Message> futureMessage = send(message, messageChannel);
        futureMessage.thenAccept(msg -> new TempMessageDeletionTask(msg).schedule());
        return futureMessage;
    }

    public CompletableFuture<Message> sendTemporary(MessageEmbed messageEmbed, User user) {
        return executeForUser(user, privateChannel -> sendTemporary(messageEmbed, privateChannel));
    }

    public CompletableFuture<Message> sendTemporary(String message, User user) {
        return executeForUser(user, privateChannel -> sendTemporary(message, privateChannel));
    }

    public CompletableFuture<Message> sendTemporary(MessageEmbed messageEmbed, Guild guild) {
        CompletableFuture<Message> futureMessage = send(messageEmbed, guild);
        futureMessage.thenAccept(message -> new TempMessageDeletionTask(message).schedule());
        return futureMessage;
    }

    public CompletableFuture<Message> sendTemporary(EmbedBuilder embedBuilder, Guild guild) {
        CompletableFuture<Message> futureMessage = send(embedBuilder, guild);
        futureMessage.thenAccept(message -> new TempMessageDeletionTask(message).schedule());
        return futureMessage;
    }

    public CompletableFuture<Message> sendTemporary(String message, Guild guild) {
        CompletableFuture<Message> futureMessage = send(message, guild);
        futureMessage.thenAccept(msg -> new TempMessageDeletionTask(msg).schedule());
        return futureMessage;
    }

    public void sendWrapped(String message, String wrapper, MessageChannel channel) {
        if (message.length() < limit) {
            sendInternal(channel, wrapper + message + wrapper);
        } else {
            List<String> outputParts = separateMessage(message);
            outputParts.forEach(part -> sendInternal(channel, wrapper + part + wrapper));
        }
    }

    public void sendWrapped(String message, String wrapper, User user) {
        if (message.length() < limit) {
            user.openPrivateChannel().queue(channel -> sendInternal(channel, wrapper + message + wrapper));
        } else {
            List<String> outputParts = separateMessage(message);
            outputParts.forEach(part -> user.openPrivateChannel().queue(channel -> sendInternal(channel, wrapper + part + wrapper)));
        }
    }

    public List<CompletableFuture<Message>> sendToActiveGuilds(MessageEmbed message, Session session) {
        GuildManager guildManager = Botify.get().getGuildManager();
        Set<Guild> activeGuilds = guildManager.getActiveGuilds(session);
        int numberOfActiveGuilds = activeGuilds.size();
        List<CompletableFuture<Message>> futureMessages = Lists.newArrayListWithCapacity(numberOfActiveGuilds);

        logger.info("Sending message to " + numberOfActiveGuilds + " active guilds.");
        for (Guild activeGuild : activeGuilds) {
            if (activeGuild != null) {
                futureMessages.add(send(message, activeGuild));
            }
        }

        return futureMessages;
    }

    public CompletableFuture<Message> executeMessageAction(MessageChannel channel, Function<MessageChannel, MessageAction> function) {
        CompletableFuture<Message> futureMessage = new CompletableFuture<>();
        try {
            if (channel instanceof TextChannel) {
                TextChannel textChannel = (TextChannel) channel;
                Guild guild = textChannel.getGuild();
                Member selfMember = guild.getSelfMember();
                if (!(selfMember.hasAccess(textChannel) && textChannel.canTalk(selfMember))) {
                    logger.warn(String.format("Can't execute message actions for channel %s on guild %s", textChannel, guild));
                    futureMessage.cancel(false);
                    return futureMessage;
                }
            }

            MessageAction messageAction = function.apply(channel);
            messageAction.queue(futureMessage::complete, e -> {
                handleError(e, channel);
                futureMessage.completeExceptionally(e);
            });
        } catch (InsufficientPermissionException e) {
            Permission permission = e.getPermission();
            if (permission == Permission.MESSAGE_WRITE || permission == Permission.MESSAGE_READ) {
                if (channel instanceof TextChannel) {
                    logger.warn("Unable to send messages to guild " + ((TextChannel) channel).getGuild());
                } else {
                    logger.warn("Unable to send messages to " + channel);
                }
                futureMessage.completeExceptionally(e);
            } else {
                StringBuilder errorMessage = new StringBuilder("Missing permission ").append(permission);
                if (channel instanceof TextChannel) {
                    errorMessage.append(" on guild ").append(((TextChannel) channel).getGuild());
                }
                logger.warn(errorMessage.toString());

                futureMessage.completeExceptionally(e);

                if (permission != Permission.VIEW_CHANNEL) {
                    String message = "Bot is missing permission: " + permission.getName();

                    RECURSION_PREVENTION_INVOKER.invoke(RECURSION_PREVENTION_MODE, () -> {
                        send(message, channel);
                    });
                }
            }
        } catch (Exception e) {
            handleError(e, channel);
            futureMessage.completeExceptionally(e);
        }

        return futureMessage;
    }

    public CompletableFuture<Message> executeMessageAction(User user, Function<MessageChannel, MessageAction> function) {
        return executeForUser(user, privateChannel -> executeMessageAction(privateChannel, function));
    }

    public CompletableFuture<Message> executeMessageAction(Guild guild, Function<MessageChannel, MessageAction> function) {
        TextChannel textChannel = getTextChannelForGuild(guild);

        if (textChannel == null) {
            logger.warn("Unable to send any messages to guild " + guild.getName() + " (" + guild.getId() + ")");
            return CompletableFuture.failedFuture(new CancellationException());
        } else {
            return executeMessageAction(textChannel, function);
        }
    }

    public MessageEmbed buildEmbed(EmbedBuilder embedBuilder) {
        embedBuilder.setColor(ColorSchemeProperty.getColor());
        return embedBuilder.build();
    }

    private CompletableFuture<Message> executeForUser(User user, Function<PrivateChannel, CompletableFuture<Message>> action) {
        CompletableFuture<Message> futureMessage = new CompletableFuture<>();
        user.openPrivateChannel().queue(channel -> {
            CompletableFuture<Message> future = action.apply(channel);
            future.whenComplete((msg, e) -> {
                if (e != null) {
                    futureMessage.completeExceptionally(e);
                } else {
                    futureMessage.complete(msg);
                }
            });
        }, futureMessage::completeExceptionally);

        return futureMessage;
    }

    private CompletableFuture<Message> sendInternal(MessageChannel channel, String text) {
        return executeMessageAction(channel, c -> c.sendMessage(text));
    }

    private CompletableFuture<Message> sendInternal(MessageChannel channel, MessageEmbed messageEmbed) {
        return executeMessageAction(channel, c -> c.sendMessage(messageEmbed));
    }

    private void handleError(Throwable e, MessageChannel channel) {
        if (e instanceof ErrorResponseException) {
            if (e.getCause() instanceof SocketTimeoutException) {
                logger.warn("Timeout sending message to channel " + channel);
            } else {
                logger.warn(String.format("Error response msg: %s cause: %s: %s sending message to channel %s",
                    e.getMessage(),
                    e.getCause(),
                    e.getCause() != null ? e.getCause().getMessage() : "null",
                    channel));
            }
        } else {
            logger.error("Unexpected exception sending message to channel " + channel, e);
        }
    }

    private TextChannel getTextChannelForGuild(Guild guild) {
        Botify botify = Botify.get();
        GuildContext guildContext = botify.getGuildManager().getContextForGuild(guild);

        // fetch the default text channel from the customised property
        GuildPropertyManager guildPropertyManager = botify.getGuildPropertyManager();
        AbstractGuildProperty defaultTextChannelProperty = guildPropertyManager.getProperty("defaultTextChannelId");
        if (defaultTextChannelProperty != null) {
            String defaultTextChannelId = (String) StaticSessionProvider.invokeWithSession(session -> {
                return defaultTextChannelProperty.get(guildContext.getSpecification(session));
            });

            if (!Strings.isNullOrEmpty(defaultTextChannelId)) {
                TextChannel textChannelById = guild.getTextChannelById(defaultTextChannelId);
                if (textChannelById != null && textChannelById.canTalk()) {
                    return textChannelById;
                }
            }
        }

        // check if the guild's playback has a current communication text channel
        MessageChannel playbackCommunicationChannel = guildContext.getPlayback().getCommunicationChannel();
        if (playbackCommunicationChannel instanceof TextChannel && ((TextChannel) playbackCommunicationChannel).canTalk()) {
            return (TextChannel) playbackCommunicationChannel;
        }

        // use guild default defined by discord
        TextChannel defaultChannel = guild.getDefaultChannel();
        if (defaultChannel != null && defaultChannel.canTalk()) {
            return defaultChannel;
        } else {
            TextChannel systemChannel = guild.getSystemChannel();
            if (systemChannel != null && systemChannel.canTalk()) {
                return systemChannel;
            }
        }

        List<TextChannel> availableChannels = guild.getTextChannels().stream().filter(TextChannel::canTalk).collect(Collectors.toList());
        if (availableChannels.isEmpty()) {
            return null;
        } else {
            return availableChannels.get(0);
        }
    }

    private List<String> separateMessage(String message) {
        List<String> outputParts = Lists.newArrayList();
        StringList paragraphs = StringList.separateString(message, "\n");

        for (int i = 0; i < paragraphs.size(); i++) {
            String paragraph = paragraphs.get(i);
            if (paragraph.length() + System.lineSeparator().length() < limit) {
                // check that paragraph is not an empty line
                if (!paragraph.isBlank()) {
                    if (i < paragraphs.size() - 1) paragraph = paragraph + System.lineSeparator();
                    fillPart(outputParts, paragraph);
                }
            } else {
                // if the paragraph is too long separate into sentences
                StringList sentences = StringList.separateString(paragraph, "\\. ");
                for (String sentence : sentences) {
                    if (sentence.length() < limit) {
                        fillPart(outputParts, sentence);
                    } else {
                        // if the sentence is too long split into words
                        StringList words = StringList.separateString(sentence, " ");

                        for (String word : words) {
                            if (word.length() < limit) {
                                fillPart(outputParts, word);
                            } else {
                                StringList chars = StringList.splitChars(word);
                                for (String charString : chars) {
                                    fillPart(outputParts, charString);
                                }
                            }
                        }
                    }
                }
            }
        }

        return outputParts;
    }

    private void fillPart(List<String> outputParts, String s) {
        if (outputParts.isEmpty()) {
            outputParts.add("");
        }

        int currentPart = outputParts.size() - 1;

        if (outputParts.get(currentPart).length() + s.length() < limit) {
            outputParts.set(currentPart, outputParts.get(currentPart) + s);
        } else {
            outputParts.add(s);
        }
    }

    private class TempMessageDeletionTask {

        private final Message message;

        private TempMessageDeletionTask(Message message) {
            this.message = message;
        }

        private void schedule() {
            int timeoutSeconds;
            try {
                timeoutSeconds = getTimeout();
            } catch (Exception e) {
                logger.error("Exception loading tempMessageTimeout property", e);
                return;
            }

            if (timeoutSeconds > 0) {
                try {
                    message.delete().queueAfter(timeoutSeconds, TimeUnit.SECONDS, v -> {
                    }, this::logError);
                } catch (InsufficientPermissionException e) {
                    logger.warn(String.format("Insufficient permission to delete temp message %s on guild %s", message, message.getGuild()));
                } catch (Exception e) {
                    logError(e);
                }
            }
        }

        private int getTimeout() {
            if (message.isFromType(ChannelType.TEXT)) {
                return StaticSessionProvider.invokeWithSession(session -> {
                    Botify botify = Botify.get();
                    GuildPropertyManager guildPropertyManager = botify.getGuildPropertyManager();
                    AbstractGuildProperty tempMessageTimeoutProperty = guildPropertyManager.getProperty("tempMessageTimeout");
                    if (tempMessageTimeoutProperty != null) {
                        Guild guild = message.getGuild();
                        GuildSpecification specification = botify.getGuildManager().getContextForGuild(guild).getSpecification(session);
                        return (int) tempMessageTimeoutProperty.get(specification);
                    }

                    return TempMessageTimeoutProperty.DEFAULT_FALLBACK;
                });
            }

            return TempMessageTimeoutProperty.DEFAULT_FALLBACK;
        }

        private void logError(Throwable e) {
            logger.warn(String.format("Unable to delete temp message %s on guild %s. %s: %s%s",
                message,
                message.getGuild(),
                e.getClass().getSimpleName(),
                e.getMessage(),
                e.getCause() != null
                    ? String.format("; caused by %s: %s", e.getCause().getClass().getSimpleName(), e.getCause().getMessage())
                    : ""));
        }
    }

}
