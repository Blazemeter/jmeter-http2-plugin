package com.hpe.simulap.http2.sampler.gui;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

import org.apache.jmeter.util.JMeterUtils;
import org.apache.jmeter.util.LocaleChangeEvent;
import org.apache.jmeter.util.LocaleChangeListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTTP2RequestPanelResourceBundle implements LocaleChangeListener {

	private static final Logger log = LoggerFactory.getLogger(HTTP2RequestPanelResourceBundle.class);
	private Locale loc = Locale.getDefault();
	private String baseName = null;
	private ResourceBundle rb = null;

	public HTTP2RequestPanelResourceBundle(String initBaseName) {
		super();
		this.loc = JMeterUtils.getLocale();
		this.baseName = initBaseName;

		JMeterUtils.addLocaleChangeListener((LocaleChangeListener) this);

		this.initResourceBundle();
	}

	private void initResourceBundle() {
		ResourceBundle newrb = null;
		try {
			newrb = ResourceBundle.getBundle(this.baseName, loc);
			this.rb = newrb;
		} catch (MissingResourceException e) {
			log.error("MissingResourceException: {}", e);
		}
	}

	public String getString(String key) {
		if (this.rb == null) {
			log.info("resource bundle is null");
			return key;
		} else {
			return this.rb.getString(key);
		}
	}

	@Override
	public void localeChanged(LocaleChangeEvent arg0) {
		this.loc = arg0.getLocale();//JMeterUtils.getLocale();
		this.initResourceBundle();
	}

}
