package com.plugish.woominecraft;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

import com.moandjiezana.toml.Toml;

public final class Config {

	private Toml toml;
	
	public Toml getToml() {
		return toml;
	}
	
	public Config() {
		this.toml = new Toml();
	}
	
	public Config loadDefaultConfig() {
		File file = new File("plugins/WooMinecraft/config.toml");
		if (! file.getParentFile().exists()) {
			file.getParentFile().mkdirs();
		}
		
		if (! file.exists()) {
			try (final InputStream input = WooMinecraft.instance.getClass().getResourceAsStream("/" + file.getName())) {
                if (input != null) {
                    Files.copy(input, file.toPath());
                } else {
                    file.createNewFile();
                }
            } catch (final IOException ex) {
                ex.printStackTrace();
                return this;
            }
		}
		
		this.toml.read(file);
		return this;
	}
		
}
