package uk.appsthatmatter.koonj.notification;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;

public final class EmailAddress {
    private EmailAddress() {}

    public static boolean valid(String address) {
        if (address == null || address.length() > 320 || address.chars().anyMatch(Character::isISOControl)) return false;
        try {
            var mailbox = new InternetAddress(address, true);
            mailbox.validate();
            return !mailbox.isGroup() && address.equals(mailbox.getAddress());
        } catch (AddressException failure) {
            return false;
        }
    }

}
