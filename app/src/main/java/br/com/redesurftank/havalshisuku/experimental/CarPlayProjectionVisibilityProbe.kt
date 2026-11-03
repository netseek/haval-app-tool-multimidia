package br.com.redesurftank.havalshisuku.experimental

/** Read-only OEM task visibility, shared by observation and the final idle-host guard. */
internal object CarPlayProjectionVisibilityProbe {
    /**
     * Only an explicitly hidden, completely framed task is negative visual evidence.
     * This says nothing about phone/session absence; DCM and Bluetooth checks remain separate.
     * The output is bounded to two fields; unfamiliar/truncated input stays unknown.
     */
    fun capture(): String = """
        cpb_stacks=§(am stack list 2>/dev/null) || exit 1
        cpb_projection_visuals=§(printf '%s\n' "§cpb_stacks" | (
          set -f
          LC_ALL=C
          export LC_ALL
          cpb_cp=0; cpb_aa=0; cpb_stack_count=0; cpb_framed=0; cpb_bad=0
          cpb_lines=0; cpb_bytes=0
          while IFS= read -r cpb_line || [ -n "§cpb_line" ]; do
            cpb_lines=§((cpb_lines+1))
            cpb_bytes=§((cpb_bytes+§{#cpb_line}+1))
            if [ "§cpb_lines" -gt 4096 ] || [ "§cpb_bytes" -gt 262144 ]; then cpb_bad=1; break; fi
            set -- §cpb_line
            case "§{1-}" in Error|Error:*|Exception*|SecurityException*|java.*) cpb_bad=1; continue;; esac
            case "§cpb_line" in
              'Stack id='*)
                cpb_framed=1
                printf '%s\n' "§cpb_line" | grep -Eq '^Stack id=[0-9]+[[:space:]]' || cpb_framed=0
                printf '%s\n' "§cpb_line" | grep -Eq ' displayId=[0-9]+([[:space:]]|§)' || cpb_framed=0
                cpb_display_count=0
                for cpb_field do
                  case "§cpb_field" in displayId=*) cpb_display_count=§((cpb_display_count+1));; esac
                done
                if [ "§cpb_display_count" -ne 1 ]; then cpb_framed=0; fi
                if [ "§cpb_framed" -ne 1 ]; then cpb_bad=1; fi
                cpb_stack_count=§((cpb_stack_count+1))
                continue;;
            esac
            cpb_iscp=0; cpb_isaa=0
            case "§cpb_line" in *com.ts.carplay.app*) cpb_iscp=1;; esac
            case "§cpb_line" in *com.ts.androidauto*|*com.google.android.projection.gearhead*) cpb_isaa=1;; esac
            if [ "§cpb_iscp" -eq 0 ] && [ "§cpb_isaa" -eq 0 ]; then continue; fi
            cpb_visible=2; cpb_visible_count=0
            for cpb_field do
              case "§cpb_field" in
                visible=*)
                  cpb_visible_count=§((cpb_visible_count+1))
                  case "§cpb_field" in visible=false) cpb_visible=0;; visible=true) cpb_visible=1;; *) cpb_visible=2;; esac;;
              esac
            done
            if [ "§cpb_framed" -ne 1 ] || [ "§cpb_visible_count" -ne 1 ]; then cpb_visible=2; fi
            printf '%s\n' "§cpb_line" | grep -Eq '^[[:space:]]+taskId=[0-9]+: [A-Za-z0-9_]+([.][A-Za-z0-9_]+)+/[A-Za-z0-9_.§]+ bounds=\[-?[0-9]+,-?[0-9]+\]\[-?[0-9]+,-?[0-9]+\] userId=[0-9]+([[:space:]]|§)' || cpb_visible=2
            printf '%s\n' "§cpb_line" | grep -Eq ' topActivity=(ComponentInfo\{[A-Za-z0-9_]+([.][A-Za-z0-9_]+)+/[A-Za-z0-9_.§]+\}|null)§' || cpb_visible=2
            if [ "§cpb_iscp" -eq 1 ] && [ "§cpb_visible" -gt "§cpb_cp" ]; then cpb_cp=§cpb_visible; fi
            if [ "§cpb_isaa" -eq 1 ] && [ "§cpb_visible" -gt "§cpb_aa" ]; then cpb_aa=§cpb_visible; fi
          done
          if [ "§cpb_stack_count" -eq 0 ] || [ "§cpb_bad" -ne 0 ]; then cpb_cp=2; cpb_aa=2; fi
          if [ "§cpb_cp" -eq 2 ]; then cpb_cp=unknown; fi
          if [ "§cpb_aa" -eq 2 ]; then cpb_aa=unknown; fi
          printf 'cp_visual=%s\naa_visual=%s\n' "§cpb_cp" "§cpb_aa"
        )) || exit 1
    """.trimIndent().replace('§', '$') + "\n"

    fun requireClear(): String = capture() + """
        test "§cpb_projection_visuals" = "§(printf 'cp_visual=0\naa_visual=0')" || exit 1
    """.trimIndent().replace('§', '$') + "\n"
}
