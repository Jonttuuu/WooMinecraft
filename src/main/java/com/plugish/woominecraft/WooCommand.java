package com.plugish.woominecraft;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ConsoleCommandSource;

import net.kyori.adventure.text.Component;

public class WooCommand implements SimpleCommand {

	@Override
	public void execute( final Invocation invocation ) {
		try {
			String msg;
			boolean checkResults = WooMinecraft.instance.check();

			if ( !checkResults ) {
				msg = "No purchases available - please try again soon.";
			} else {
				msg = "All purchases processed.";
			}

			invocation.source().sendMessage(Component.text( msg ));
		} catch ( Exception e ) {
			WooMinecraft.instance.getLogger().warn( e.getMessage() );
			e.printStackTrace();
		}
	}

	@Override
	public boolean hasPermission(final Invocation invocation) {
		return invocation.source() instanceof ConsoleCommandSource;
	}
}
