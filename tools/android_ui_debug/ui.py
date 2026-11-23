"""Parse UIAutomator XML and find/tap nodes without screenshots."""

from __future__ import annotations

import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from typing import Iterable, Optional


_BOUNDS_RE = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")


@dataclass(frozen=True)
class Bounds:
    left: int
    top: int
    right: int
    bottom: int

    @property
    def center(self) -> tuple[int, int]:
        return ((self.left + self.right) // 2, (self.top + self.bottom) // 2)

    @property
    def width(self) -> int:
        return max(0, self.right - self.left)

    @property
    def height(self) -> int:
        return max(0, self.bottom - self.top)

    def __str__(self) -> str:
        return f"[{self.left},{self.top}][{self.right},{self.bottom}]"


@dataclass
class UiNode:
    text: str = ""
    content_desc: str = ""
    resource_id: str = ""
    class_name: str = ""
    package: str = ""
    clickable: bool = False
    enabled: bool = True
    focused: bool = False
    selected: bool = False
    checked: bool = False
    bounds: Optional[Bounds] = None
    attributes: dict[str, str] = None  # type: ignore[assignment]

    def __post_init__(self) -> None:
        if self.attributes is None:
            self.attributes = {}

    @property
    def label(self) -> str:
        return self.text or self.content_desc

    @property
    def center(self) -> tuple[int, int]:
        if self.bounds is None:
            raise ValueError(f"node has no bounds: {self!r}")
        return self.bounds.center

    def matches(
        self,
        *,
        text: Optional[str] = None,
        contains: Optional[str] = None,
        regex: Optional[str] = None,
        desc: Optional[str] = None,
        resource_id: Optional[str] = None,
        class_name: Optional[str] = None,
        clickable: Optional[bool] = None,
        enabled: Optional[bool] = None,
    ) -> bool:
        haystacks = (self.text, self.content_desc)
        if text is not None and text not in haystacks:
            return False
        if contains is not None and not any(contains in h for h in haystacks):
            return False
        if regex is not None and not any(re.search(regex, h) for h in haystacks):
            return False
        if desc is not None and self.content_desc != desc:
            return False
        if resource_id is not None and self.resource_id != resource_id:
            return False
        if class_name is not None and self.class_name != class_name:
            return False
        if clickable is not None and self.clickable != clickable:
            return False
        if enabled is not None and self.enabled != enabled:
            return False
        return True

    def __str__(self) -> str:
        bits = [f"class={self.class_name!r}"]
        if self.text:
            bits.append(f"text={self.text!r}")
        if self.content_desc:
            bits.append(f"desc={self.content_desc!r}")
        if self.resource_id:
            bits.append(f"id={self.resource_id!r}")
        if self.bounds:
            bits.append(f"bounds={self.bounds}")
        bits.append(f"clickable={self.clickable}")
        return " ".join(bits)


def parse_ui_xml(xml_text: str) -> list[UiNode]:
    """Return every node in a UIAutomator dump, depth-first."""
    if not xml_text or not xml_text.strip():
        return []
    root = ET.fromstring(xml_text)
    nodes: list[UiNode] = []
    for element in root.iter("node"):
        attrs = dict(element.attrib)
        match = _BOUNDS_RE.search(attrs.get("bounds", ""))
        bounds = Bounds(*(int(g) for g in match.groups())) if match else None
        nodes.append(
            UiNode(
                text=attrs.get("text", ""),
                content_desc=attrs.get("content-desc", ""),
                resource_id=attrs.get("resource-id", ""),
                class_name=attrs.get("class", ""),
                package=attrs.get("package", ""),
                clickable=attrs.get("clickable") == "true",
                enabled=attrs.get("enabled") != "false",
                focused=attrs.get("focused") == "true",
                selected=attrs.get("selected") == "true",
                checked=attrs.get("checked") == "true",
                bounds=bounds,
                attributes=attrs,
            )
        )
    return nodes


def find_nodes(
    nodes: Iterable[UiNode],
    *,
    text: Optional[str] = None,
    contains: Optional[str] = None,
    regex: Optional[str] = None,
    desc: Optional[str] = None,
    resource_id: Optional[str] = None,
    class_name: Optional[str] = None,
    clickable: Optional[bool] = None,
    enabled: Optional[bool] = None,
    require_bounds: bool = True,
) -> list[UiNode]:
    found = [
        n
        for n in nodes
        if n.matches(
            text=text,
            contains=contains,
            regex=regex,
            desc=desc,
            resource_id=resource_id,
            class_name=class_name,
            clickable=clickable,
            enabled=enabled,
        )
    ]
    if require_bounds:
        found = [n for n in found if n.bounds is not None]
    return found


def first_node(nodes: Iterable[UiNode], **kwargs) -> Optional[UiNode]:
    found = find_nodes(nodes, **kwargs)
    return found[0] if found else None


def summarize(nodes: Iterable[UiNode], limit: int = 80) -> str:
    lines = []
    for node in list(nodes)[:limit]:
        if node.text or node.content_desc or node.resource_id:
            lines.append(str(node))
    return "\n".join(lines) if lines else "(no labelled nodes)"
