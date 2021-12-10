/*
 * Woo Minecraft Donation plugin
 * Author:	   Jerry Wood
 * Author URI: http://plugish.com
 * License:	   GPLv2
 * 
 * Copyright 2014 All rights Reserved
 * 
 */
package com.plugish.woominecraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.inject.Inject;
import com.moandjiezana.toml.Toml;
import com.plugish.woominecraft.pojo.Order;
import com.plugish.woominecraft.pojo.WMCPojo;
import com.plugish.woominecraft.pojo.WMCProcessedOrders;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.proxy.ProxyServer;

import org.slf4j.Logger;

import okhttp3.*;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class WooMinecraft {

	static WooMinecraft instance;

	private final ProxyServer server;
	private final Logger logger;
	private Toml config;

	public ProxyServer getServer() {
		return this.server;
	}

	public Logger getLogger() {
		return this.logger;
	}

	public Toml getConfig() {
		return this.config;
	}

	@Inject
	public WooMinecraft(final ProxyServer server, final Logger logger) {
		instance = this;
		this.server = server;
		this.logger = logger;
	}

	@Subscribe
	public void onProxyInitialize(final ProxyInitializeEvent event) {
		this.config = new Config().loadDefaultConfig().getToml();

		// Load the commands.
		getServer().getCommandManager().register(
			getServer().getCommandManager()
			.metaBuilder( "woocheck" )
			.build(),
			new WooCommand()
		);

		// Setup the scheduler
		getServer().getScheduler().buildTask(this, () -> {
			try {
				check();
			} catch ( Exception e ) {
				instance.getLogger().warn( e.getMessage() );
				e.printStackTrace();
			}
		})
		.repeat(config.getLong("update_interval"), TimeUnit.SECONDS)
		.schedule();
	}

	/**
	 * Validates the basics needed in the config.yml file.
	 *
	 * Multiple reports of user configs not having keys etc... so this will ensure they know of this
	 * and will not allow checks to continue if the required data isn't set in the config.
	 *
	 * @throws Exception Reason for failing to validate the config.
	 */
	private void validateConfig() throws Exception {

		if ( 1 > this.getConfig().getString( "url" ).length() ) {
			throw new Exception( "Server URL is empty, check config." );
		} else if ( this.getConfig().getString( "url" ).equals( "http://playground.dev" ) ) {
			throw new Exception( "URL is still the default URL, check config." );
		} else if ( 1 > this.getConfig().getString( "key" ).length() ) {
			throw new Exception( "Server Key is empty, this is insecure, check config." );
		}
	}

	/**
	 * Gets the site URL
	 *
	 * @return URL
	 * @throws Exception Why the URL failed.
	 */
	private URL getSiteURL() throws Exception {
		return new URL( getConfig().getString( "url" ) + "/wp-json/wmc/v1/server/" + getConfig().getString( "key" ) );
	}

	/**
	 * Checks all online players against the
	 * website's database looking for pending donation deliveries
	 *
	 * @return boolean
	 * @throws Exception Why the operation failed.
	 */
	boolean check() throws Exception {

		// Make 100% sure the config has at least a key and url
		this.validateConfig();

		// Contact the server.
		String pendingOrders = getPendingOrders();

		// Server returned an empty response, bail here.
		if ( pendingOrders.isEmpty() ) {
			return false;
		}

		// Create new object from JSON response.
		Gson gson = new GsonBuilder().create();
		WMCPojo wmcPojo = gson.fromJson( pendingOrders, WMCPojo.class );
		List<Order> orderList = wmcPojo.getOrders();

		// Log if debugging is enabled.
		wmc_log( pendingOrders );

		// Validate we can indeed process what we need to.
		if ( wmcPojo.getData() != null ) {
			// We have an error, so we need to bail.
			wmc_log( "Code:" + wmcPojo.getCode(), 3 );
			throw new Exception( wmcPojo.getMessage() );
		}

		if ( orderList == null || orderList.isEmpty() ) {
			wmc_log( "No orders to process.", 2 );
			return false;
		}

		// foreach ORDERS in JSON feed
		List<Integer> processedOrders = new ArrayList<>();
		for ( Order order : orderList ) {

			// Walk over all commands and run them at the next available tick.
			for ( String command : order.getCommands() ) {
				getServer().getCommandManager().executeAsync(getServer().getConsoleCommandSource(), command);
			}

			wmc_log( "Adding item to list - " + order.getOrderId() );
			processedOrders.add( order.getOrderId() );
			wmc_log( "Processed length is " + processedOrders.size() );
		}

		// If it's empty, we skip it.
		if ( processedOrders.isEmpty() ) {
			return false;
		}

		// Send/update processed orders.
		return sendProcessedOrders( processedOrders );
	}

	/**
	 * Sends the processed orders to the site.
	 *
	 * @param processedOrders A list of order IDs which were processed.
	 * @return boolean
	 */
	private boolean sendProcessedOrders( List<Integer> processedOrders ) throws Exception {
		// Build the GSON data to send.
		Gson gson = new Gson();
		WMCProcessedOrders wmcProcessedOrders = new WMCProcessedOrders();
		wmcProcessedOrders.setProcessedOrders( processedOrders );
		String orders = gson.toJson( wmcProcessedOrders );

		// Setup the client.
		OkHttpClient client = new OkHttpClient();

		// Process stuffs now.
		RequestBody body = RequestBody.create( MediaType.parse( "application/json; charset=utf-8" ), orders );
		Request request = new Request.Builder().url( getSiteURL() ).post( body ).build();
		Response response = client.newCall( request ).execute();

		// If the body is empty we can do nothing.
		if ( null == response.body() ) {
			throw new Exception( "Received empty response from your server, check connections." );
		}

		// Get the JSON reply from the endpoint.
		WMCPojo wmcPojo = gson.fromJson( response.body().string(), WMCPojo.class );
		if ( null != wmcPojo.getCode() ) {
			wmc_log( "Received error when trying to send post data:" + wmcPojo.getCode(), 3 );
			throw new Exception( wmcPojo.getMessage() );
		}

		return true;
	}

	/**
	 * If debugging is enabled.
	 *
	 * @return boolean
	 */
	private boolean isDebug() {
		return getConfig().getBoolean( "debug" );
	}

	/**
	 * Gets pending orders from the WordPress JSON endpoint.
	 *
	 * @return String
	 * @throws Exception On failure.
	 */
	private String getPendingOrders() throws Exception {
		URL baseURL = getSiteURL();
		BufferedReader in;
		try {
			in = new BufferedReader(new InputStreamReader(baseURL.openStream()));
		} catch( FileNotFoundException e ) {
			String msg = e.getMessage().replace( getConfig().getString( "key" ), "privateKey" );
			throw new FileNotFoundException( msg );
		}

		StringBuilder buffer = new StringBuilder();

		// Walk over each line of the response.
		String line;
		while ( ( line = in.readLine() ) != null ) {
			buffer.append( line );
		}

		in.close();

		return buffer.toString();
	}

	/**
	 * Log stuffs.
	 *
	 * @param message The message to log.
	 */
	private void wmc_log(String message) {
		this.wmc_log( message, 1 );
	}

	/**
	 * Log stuffs.
	 *
	 * @param message The message to log.
	 * @param level The level to log it at.
	 */
	private void wmc_log(String message, Integer level) {

		if ( ! isDebug() ) {
			return;
		}

		switch ( level ) {
			case 1:
				this.getLogger().info( message );
				break;
			case 2:
				this.getLogger().warn( message );
				break;
			case 3:
				this.getLogger().error( message );
				break;
		}
	}
}
