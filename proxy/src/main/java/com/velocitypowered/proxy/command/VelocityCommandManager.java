/*
 * Copyright (C) 2018-2023 Velocity Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.velocitypowered.proxy.command;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.MoreExecutors;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.Message;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.Command;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandResult;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.VelocityBrigadierMessage;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.command.PostCommandInvocationEvent;
import com.velocitypowered.api.plugin.PluginManager;
import com.velocitypowered.proxy.command.brigadier.VelocityBrigadierCommandWrapper;
import com.velocitypowered.proxy.command.registrar.BrigadierCommandRegistrar;
import com.velocitypowered.proxy.command.registrar.CommandRegistrar;
import com.velocitypowered.proxy.command.registrar.RawCommandRegistrar;
import com.velocitypowered.proxy.command.registrar.SimpleCommandRegistrar;
import com.velocitypowered.proxy.event.VelocityEventManager;
import com.velocitypowered.proxy.plugin.virtual.VelocityVirtualPlugin;
import io.netty.util.concurrent.FastThreadLocalThread;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.checkerframework.checker.lock.qual.GuardedBy;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

/**
 * Implements Velocity's command handler.
 */
public class VelocityCommandManager implements CommandManager {

  private final @GuardedBy("lock") CommandDispatcher<CommandSource> dispatcher;
  private final ReadWriteLock lock;

  private final VelocityEventManager eventManager;
  private final List<CommandRegistrar<?>> registrars;
  private final SuggestionsProvider<CommandSource> suggestionsProvider;
  private final CommandGraphInjector<CommandSource> injector;
  private final Map<String, CommandMeta> commandMetas;
  private final PluginManager pluginManager;

  /**
   * Constructs a command manager.
   *
   * @param eventManager the event manager
   */
  public VelocityCommandManager(final VelocityEventManager eventManager,
      PluginManager pluginManager) {
    this.pluginManager = pluginManager;
    this.lock = new ReentrantReadWriteLock();
    this.dispatcher = new CommandDispatcher<>();
    this.eventManager = Preconditions.checkNotNull(eventManager);
    final RootCommandNode<CommandSource> root = this.dispatcher.getRoot();
    this.registrars = ImmutableList.of(
        new BrigadierCommandRegistrar(root, this.lock.writeLock()),
        new SimpleCommandRegistrar(root, this.lock.writeLock()),
        new RawCommandRegistrar(root, this.lock.writeLock()));
    this.suggestionsProvider = new SuggestionsProvider<>(this.dispatcher, this.lock.readLock());
    this.injector = new CommandGraphInjector<>(this.dispatcher, this.lock.readLock());
    this.commandMetas = new ConcurrentHashMap<>();
  }

  public void setAnnounceProxyCommands(boolean announceProxyCommands) {
    this.suggestionsProvider.setAnnounceProxyCommands(announceProxyCommands);
  }

  @Override
  public CommandMeta.Builder metaBuilder(final String alias) {
    Preconditions.checkNotNull(alias, "alias");
    return new VelocityCommandMeta.Builder(alias);
  }

  @Override
  public CommandMeta.Builder metaBuilder(final BrigadierCommand command) {
    Preconditions.checkNotNull(command, "command");
    return new VelocityCommandMeta.Builder(command.getNode().getName());
  }

  @Override
  public void register(final BrigadierCommand command) {
    Preconditions.checkNotNull(command, "command");
    register(metaBuilder(command).build(), command);
  }

  @Override
  public void register(final CommandMeta meta, final Command command) {
    Preconditions.checkNotNull(meta, "meta");
    Preconditions.checkNotNull(command, "command");

    final List<CommandRegistrar<?>> commandRegistrars = this.implementedRegistrars(command);
    if (commandRegistrars.isEmpty()) {
      throw new IllegalArgumentException(
              command + " does not implement a registrable Command subinterface");
    } else if (commandRegistrars.size() > 1) {
      final String implementedInterfaces = commandRegistrars.stream()
              .map(CommandRegistrar::registrableSuperInterface)
              .map(Class::getSimpleName)
              .collect(Collectors.joining(", "));
      throw new IllegalArgumentException(
              command + " implements multiple registrable Command subinterfaces: "
                      + implementedInterfaces);
    } else {
      this.internalRegister(commandRegistrars.get(0), command, meta);
    }
  }

  /**
   * Attempts to register the given command if it implements the
   * {@linkplain CommandRegistrar#registrableSuperInterface() registrable superinterface} of the
   * given registrar.
   *
   * @param registrar the registrar to register the command
   * @param command   the command to register
   * @param meta      the command metadata
   * @param <T>       the type of the command
   * @throws IllegalArgumentException if the registrar cannot register the command
   */
  private <T extends Command> void internalRegister(final CommandRegistrar<T> registrar,
      final Command command, final CommandMeta meta) {
    final Class<T> superInterface = registrar.registrableSuperInterface();
    registrar.register(meta, superInterface.cast(command));
    for (String alias : meta.getAliases()) {
      commandMetas.put(alias, meta);
    }
  }

  private List<CommandRegistrar<?>> implementedRegistrars(final Command command) {
    final List<CommandRegistrar<?>> registrarsFound = new ArrayList<>(2);
    for (final CommandRegistrar<?> registrar : this.registrars) {
      final Class<?> superInterface = registrar.registrableSuperInterface();
      if (superInterface.isInstance(command)) {
        registrarsFound.add(registrar);
      }
    }
    return registrarsFound;
  }

  @Override
  public void unregister(final String alias) {
    Preconditions.checkNotNull(alias, "alias");
    lock.writeLock().lock();
    try {
      // The literals of secondary aliases will preserve the children of
      // the removed literal in the graph.
      dispatcher.getRoot().removeChildByName(alias.toLowerCase(Locale.ENGLISH));
      commandMetas.remove(alias);
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public void unregister(CommandMeta meta) {
    Preconditions.checkNotNull(meta, "meta");
    lock.writeLock().lock();
    try {
      // The literals of secondary aliases will preserve the children of
      // the removed literal in the graph.
      for (String alias : meta.getAliases()) {
        final String lowercased = alias.toLowerCase(Locale.ENGLISH);
        if (commandMetas.remove(lowercased, meta)) {
          dispatcher.getRoot().removeChildByName(lowercased);
        }
      }
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public @Nullable CommandMeta getCommandMeta(String alias) {
    Preconditions.checkNotNull(alias, "alias");
    return commandMetas.get(alias);
  }

  /**
   * Fires a {@link CommandExecuteEvent}.
   *
   * @param source  the source to execute the command for
   * @param cmdLine the command to execute
   * @param invocationInfo the invocation info
   * @return the {@link CompletableFuture} of the event
   */
  public CompletableFuture<CommandExecuteEvent> callCommandEvent(final CommandSource source,
      final String cmdLine, final CommandExecuteEvent.InvocationInfo invocationInfo) {
    Preconditions.checkNotNull(source, "source");
    Preconditions.checkNotNull(cmdLine, "cmdLine");
    return eventManager.fire(new CommandExecuteEvent(source, cmdLine, invocationInfo));
  }

  private boolean executeImmediately0(final CommandSource source, final ParseResults<CommandSource> parsed) {
    Preconditions.checkNotNull(source, "source");

    CommandResult result = CommandResult.EXCEPTION;
    try {
      // The parse can fail if the requirement predicates throw
      boolean executed = dispatcher.execute(parsed) != BrigadierCommand.FORWARD;
      result = executed ? CommandResult.EXECUTED : CommandResult.FORWARDED;
      return executed;
    } catch (final CommandSyntaxException e) {
      boolean isSyntaxError = !e.getType().equals(
          CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownCommand());
      if (isSyntaxError) {
        final Message message = e.getRawMessage();
        if (message instanceof VelocityBrigadierMessage velocityMessage) {
          source.sendMessage(velocityMessage.asComponent().applyFallbackStyle(NamedTextColor.RED));
        } else {
          source.sendMessage(Component.text(e.getMessage(), NamedTextColor.RED));
        }
        result = com.velocitypowered.api.command.CommandResult.SYNTAX_ERROR;
        // This is, of course, a lie, but the API will need to change...
        return true;
      } else {
        result = CommandResult.FORWARDED;
        return false;
      }
    } catch (final Throwable e) {
      // Ugly, ugly swallowing of everything Throwable, because plugins are naughty.
      throw new RuntimeException("Unable to invoke command  " + parsed.getReader().getString() + "for " + source, e);
    } finally {
      eventManager.fireAndForget(new PostCommandInvocationEvent(source, parsed.getReader().getString(), result));
    }
  }

  @Override
  public CompletableFuture<Boolean> executeAsync(final CommandSource source, final String cmdLine) {
    Preconditions.checkNotNull(source, "source");
    Preconditions.checkNotNull(cmdLine, "cmdLine");

    CommandExecuteEvent.InvocationInfo invocationInfo = new CommandExecuteEvent.InvocationInfo(
                    CommandExecuteEvent.SignedState.UNSUPPORTED,
                    CommandExecuteEvent.Source.API
    );

    return callCommandEvent(source, cmdLine, invocationInfo).thenComposeAsync(event -> {
      CommandExecuteEvent.CommandResult commandResult = event.getResult();
      if (commandResult.isForwardToServer() || !commandResult.isAllowed()) {
        return CompletableFuture.completedFuture(false);
      }
      final ParseResults<CommandSource> parsed = this.parse(
          commandResult.getCommand().orElse(cmdLine), source);
      return CompletableFuture.supplyAsync(
          () -> executeImmediately0(source, parsed), this.getAsyncExecutor(parsed)
      );
    }, figureAsyncExecutorForParsing());
  }

  @Override
  public CompletableFuture<Boolean> executeImmediatelyAsync(
      final CommandSource source, final String cmdLine) {
    Preconditions.checkNotNull(source, "source");
    Preconditions.checkNotNull(cmdLine, "cmdLine");

    return CompletableFuture.supplyAsync(
        () -> this.parse(cmdLine, source), figureAsyncExecutorForParsing()
    ).thenCompose(
        parsed -> CompletableFuture.supplyAsync(
            () -> executeImmediately0(source, parsed), this.getAsyncExecutor(parsed)
        )
    );
  }

  @Override
  public CompletableFuture<List<String>> offerSuggestions(final CommandSource source,
      final String cmdLine) {
    return offerBrigadierSuggestions(source, cmdLine)
        .thenApply(suggestions -> Lists.transform(suggestions.getList(), Suggestion::getText));
  }

  @Override
  public CompletableFuture<Suggestions> offerBrigadierSuggestions(
      final CommandSource source, final String cmdLine) {
    Preconditions.checkNotNull(source, "source");
    Preconditions.checkNotNull(cmdLine, "cmdLine");

    final String normalizedInput = VelocityCommands.normalizeInput(cmdLine, false);
    try {
      return suggestionsProvider.provideSuggestions(normalizedInput, source);
    } catch (final Throwable e) {
      // Again, plugins are naughty
      return CompletableFuture.failedFuture(
          new RuntimeException("Unable to provide suggestions for " + cmdLine + " for " + source,
              e));
    }
  }

  /**
   * Parses the given command input.
   *
   * @param input  the normalized command input, without the leading slash ('/')
   * @param source the command source to parse the command for
   * @return the parse results
   */
  private ParseResults<CommandSource> parse(final String input, final CommandSource source) {
    final String normalizedInput = VelocityCommands.normalizeInput(input, true);
    lock.readLock().lock();
    try {
      return dispatcher.parse(normalizedInput, source);
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public Collection<String> getAliases() {
    lock.readLock().lock();
    try {
      // A RootCommandNode may only contain LiteralCommandNode children instances
      return dispatcher.getRoot().getChildren().stream()
          .map(CommandNode::getName)
          .collect(ImmutableList.toImmutableList());
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public boolean hasCommand(final String alias) {
    return getCommand(alias) != null;
  }

  @Override
  public boolean hasCommand(String alias, CommandSource source) {
    Preconditions.checkNotNull(source, "source");
    CommandNode<CommandSource> command = getCommand(alias);
    return command != null && command.canUse(source);
  }

  CommandNode<CommandSource> getCommand(final String alias) {
    Preconditions.checkNotNull(alias, "alias");
    return dispatcher.getRoot().getChild(alias.toLowerCase(Locale.ENGLISH));
  }

  @VisibleForTesting // this constitutes unsafe publication
  RootCommandNode<CommandSource> getRoot() {
    return dispatcher.getRoot();
  }

  public CommandGraphInjector<CommandSource> getInjector() {
    return injector;
  }

  private Executor getAsyncExecutor(ParseResults<CommandSource> parse) {
    Object registrant;
    if (parse.getContext().getCommand() instanceof VelocityBrigadierCommandWrapper vbcw) {
      registrant = vbcw.registrant() == null ? VelocityVirtualPlugin.INSTANCE : vbcw.registrant();
    } else {
      registrant = VelocityVirtualPlugin.INSTANCE;
    }
    return pluginManager.ensurePluginContainer(registrant).getExecutorService();
  }

  private Executor figureAsyncExecutorForParsing() {
    final Thread thread = Thread.currentThread();
    if (thread instanceof FastThreadLocalThread) {
      // we *never* want to block the Netty event loop, so use the async executor
      return pluginManager.ensurePluginContainer(VelocityVirtualPlugin.INSTANCE).getExecutorService();
    } else {
      // it's some other thread that isn't a Netty event loop thread. direct execution it is!
      return MoreExecutors.directExecutor();
    }
  }
}