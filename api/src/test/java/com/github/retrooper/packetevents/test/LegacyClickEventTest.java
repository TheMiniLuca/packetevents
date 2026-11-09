package com.github.retrooper.packetevents.test;

import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.nbt.NBTInt;
import com.github.retrooper.packetevents.protocol.nbt.NBTString;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.test.base.BaseDummyAPITest;
import com.github.retrooper.packetevents.util.adventure.AdventureSerializer;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class LegacyClickEventTest extends BaseDummyAPITest {

    @ParameterizedTest
    @MethodSource("legacyEvents")
    @DisplayName("Adventure 5에서도 1.21.4 클릭 메시지의 액션과 문자열 값을 보존한다")
    public void legacyClickEventUsesStringValue(final String action, final ClickEvent<?> event, final String value) {
        final NBTCompound click = serialize(ClientVersion.V_1_21_4, event).getCompoundTagOrNull("clickEvent");
        assertNotNull(click);
        assertEquals(new NBTString(action), click.getTagOrNull("action"));
        assertEquals(new NBTString(value), click.getTagOrNull("value"));
    }

    private static Stream<Arguments> legacyEvents() {
        return Stream.of(
                Arguments.of("run_command", ClickEvent.runCommand("/identity account list"), "/identity account list"),
                Arguments.of("suggest_command", ClickEvent.suggestCommand("/nick Example"), "/nick Example"),
                Arguments.of("change_page", ClickEvent.changePage(3), "3"));
    }

    @ParameterizedTest
    @EnumSource(value = ClientVersion.class, names = {"V_1_21_5", "V_26_3"})
    @DisplayName("최신 클릭 메시지의 책 페이지는 정수 타입을 유지한다")
    public void modernPageKeepsIntegerPayload(final ClientVersion version) {
        final NBTCompound click = serialize(version, ClickEvent.changePage(3)).getCompoundTagOrNull("click_event");
        assertNotNull(click);
        assertEquals(new NBTString("change_page"), click.getTagOrNull("action"));
        assertEquals(new NBTInt(3), click.getTagOrNull("page"));
    }

    private NBTCompound serialize(final ClientVersion version, final ClickEvent<?> event) {
        return (NBTCompound) AdventureSerializer.serializer(version).nbt().serialize(
                Component.text("account").clickEvent(event), PacketWrapper.createDummyWrapper(version));
    }
}
