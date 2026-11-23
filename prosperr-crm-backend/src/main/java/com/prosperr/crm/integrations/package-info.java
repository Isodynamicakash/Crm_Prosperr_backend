/**
 * integrations module: each user's own Google connection (Calendar now, Gmail next). Everything here runs after the
 * CRM's own transaction commits, so a Google outage never blocks booking a meeting. Off when no OAuth client is set.
 */
package com.prosperr.crm.integrations;
