package me.fixeddev.commandflow;

import me.fixeddev.commandflow.command.Command;
import me.fixeddev.commandflow.exception.ArgumentException;
import me.fixeddev.commandflow.exception.CommandUsage;
import me.fixeddev.commandflow.exception.NoPermissionsException;
import me.fixeddev.commandflow.executor.DefaultExecutor;
import me.fixeddev.commandflow.executor.Executor;
import me.fixeddev.commandflow.exception.CommandException;
import me.fixeddev.commandflow.input.InputTokenizer;
import me.fixeddev.commandflow.input.StringSpaceTokenizer;
import me.fixeddev.commandflow.part.CommandPart;
import me.fixeddev.commandflow.stack.ArgumentStack;
import me.fixeddev.commandflow.stack.SimpleArgumentStack;
import me.fixeddev.commandflow.translator.DefaultMapTranslationProvider;
import me.fixeddev.commandflow.translator.DefaultTranslator;
import me.fixeddev.commandflow.translator.Translator;
import me.fixeddev.commandflow.usage.DefaultUsageBuilder;
import me.fixeddev.commandflow.usage.UsageBuilder;

import java.util.*;

/**
 * The default implementation for {@link CommandManager} using a HashMap for the internal commandMap
 * <p>
 * This class is not threadsafe, we can't ensure that registering/executing commands on more than 1 thread concurrently works correctly
 */
public class SimpleCommandManager implements CommandManager {
    private final Map<String, Command> commandMap;

    private Authorizer authorizer;
    private InputTokenizer tokenizer;
    private Executor executor;
    private Translator translator;
    private UsageBuilder usageBuilder;

    public SimpleCommandManager(Authorizer authorizer) {
        this.authorizer = authorizer;
        commandMap = new HashMap<>();
        tokenizer = new StringSpaceTokenizer();

        executor = new DefaultExecutor();
        translator = new DefaultTranslator(new DefaultMapTranslationProvider());
        usageBuilder = new DefaultUsageBuilder();
    }

    public SimpleCommandManager() {
        this((namespace, permission) -> true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void registerCommand(Command command) {
        if (commandMap.containsKey(command.getName())) {
            throw new IllegalArgumentException("A command with the name " + command.getName() + " is already registered!");
        }

        commandMap.put(command.getName().toLowerCase(), command);

        command.getAliases().forEach(alias -> commandMap.putIfAbsent(alias.toLowerCase(), command));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void registerCommands(List<Command> commandList) {
        for (Command command : commandList) {
            registerCommand(command);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void unregisterCommand(Command command) {
        commandMap.remove(command.getName());

        for (String alias : command.getAliases()) {
            commandMap.remove(alias);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void unregisterCommands(List<Command> commands) {
        for (Command command : commands) {
            unregisterCommand(command);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void unregisterAll() {
        Set<Command> commands = new HashSet<>(commandMap.values());

        for (Command command : commands) {
            unregisterCommand(command);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<Command> getCommands() {
        return new HashSet<>(commandMap.values());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean exists(String commandName) {
        return commandMap.containsKey(commandName);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Authorizer getAuthorizer() {
        return authorizer;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setAuthorizer(Authorizer authorizer) {
        if (authorizer == null) {
            throw new IllegalArgumentException("Trying to set a null authorizer!");
        }

        this.authorizer = authorizer;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public InputTokenizer getInputTokenizer() {
        return tokenizer;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setInputTokenizer(InputTokenizer tokenizer) {
        if (tokenizer == null) {
            throw new IllegalArgumentException("Trying to set a null input tokenizer!");
        }

        this.tokenizer = tokenizer;
    }

    @Override
    public Executor getExecutor() {
        return executor;
    }

    @Override
    public void setExecutor(Executor executor) {
        if (executor == null) {
            throw new IllegalArgumentException("Trying to set a null Executor!");
        }

        this.executor = executor;
    }

    @Override
    public Translator getTranslator() {
        return translator;
    }

    @Override
    public void setTranslator(Translator translator) {
        if (translator == null) {
            throw new IllegalArgumentException("Trying to set a null Translator!");
        }

        this.translator = translator;
    }

    @Override
    public UsageBuilder getUsageBuilder() {
        return usageBuilder;
    }

    @Override
    public void setUsageBuilder(UsageBuilder usageBuilder) {
        if (usageBuilder == null) {
            throw new IllegalArgumentException("Trying to set a null UsageBuilder!");
        }

        this.usageBuilder = usageBuilder;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<Command> getCommand(String commandName) {
        return Optional.ofNullable(commandMap.get(commandName.toLowerCase()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean execute(Namespace accessor, List<String> arguments) throws CommandException {
        if (arguments == null || arguments.isEmpty()) {
            return false;
        }

        Optional<Command> optionalCommand = getCommand(arguments.get(0));

        if (!optionalCommand.isPresent()) {
            return false;
        }

        ArgumentStack stack = new SimpleArgumentStack(arguments);

        String label = stack.next();
        Command command = optionalCommand.get();

        if (!authorizer.isAuthorized(accessor, command.getPermission())) {
            throw new NoPermissionsException(command.getPermissionMessage());
        }

        CommandContext commandContext = new SimpleCommandContext(accessor, arguments);
        commandContext.setCommand(command, label);

        accessor.setObject(CommandManager.class, "commandManager", this);

        CommandPart part = command.getPart();
        try {
            part.parse(commandContext, stack);
        } catch (ArgumentException e) {
            CommandUsage usage = new CommandUsage(usageBuilder.getUsage(commandContext));
            usage.setCommand(commandContext.getCommand());

            usage.initCause(e);
            throw usage;
        }

        return executor.execute(commandContext, getUsageBuilder());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean execute(Namespace accessor, String line) throws CommandException {
        return execute(accessor, tokenizer.tokenize(line));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<String> getSuggestions(Namespace accessor, List<String> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return Collections.emptyList();
        }

        Optional<Command> optionalCommand = getCommand(arguments.get(0));

        if (!optionalCommand.isPresent()) {
            return Collections.emptyList();
        }

        arguments.remove(0);

        Command command = optionalCommand.get();

        if (!authorizer.isAuthorized(accessor, command.getPermission())) {
            return Collections.emptyList();
        }

        CommandContext commandContext = new SimpleCommandContext(accessor, arguments);
        commandContext.setCommand(command, command.getName());

        accessor.setObject(CommandManager.class, "commandManager", this);

        ArgumentStack stack = new SimpleArgumentStack(arguments);

        return command.getPart().getSuggestions(commandContext, stack);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<String> getSuggestions(Namespace accessor, String line) {
        return getSuggestions(accessor, tokenizer.tokenize(line));
    }

}
