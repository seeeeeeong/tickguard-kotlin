# AGENTS.md

## Review guidelines

- 리뷰 코멘트는 모두 **한국어**로 작성한다. 코드, 식별자, 로그와 에러 문구, 파일 경로는 원문 그대로 둔다.
- 이 서비스는 실제 증권 계좌에 주문을 넣는다. 돈이 잘못 나갈 수 있는 경로를 가장 먼저 본다:
  중복 주문, 한도 초과, 다른 전략이나 사용자 개인 보유분을 건드리는 매매, 오래되거나 미완성인 가격으로 한 판단.
- `CLAUDE.md`의 "Never do this"와 "Placing orders" 규칙을 어기는 변경은 P0 또는 P1로 표시한다.
- 코드 규칙(`CLAUDE.md`의 Conventions, Comments)은 CI의 ktlint·detekt·architecture 테스트가 검사하므로,
  그 도구가 잡지 못하는 동작상의 문제에 집중한다.
