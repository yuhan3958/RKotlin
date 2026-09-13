# 컴파일러 책임과 수정 위치

컴파일 흐름은 `ModuleLoader → Lexer → Parser → SemanticAnalyzer → IrLowering → CBackend`이다.
각 단계는 다음 단계에 필요한 모델을 만들며, 진단은 해당 규칙을 소유한 단계에서 발생시킨다.

## 의미 분석

| 파일 (`src/main/kotlin/semantic/`) | 책임과 수정할 작업 |
| --- | --- |
| `SemanticRegistration.kt` | 클래스·필드·함수·생성자 심볼 등록, 합성 접근자 생성, 선언 중복 검사 |
| `SemanticInheritance.kt` | 부모 클래스·인터페이스 연결, 상속 순환, override와 인터페이스 구현 계약 검사 |
| `SemanticTypeResolver.kt` | 소스의 타입 참조 해석, 타입 인자 개수, nullable·포인터 타입 구성 |
| `SemanticRules.kt` | 접근 권한, 대입 가능성, 제네릭 타입 대입, 인자와 조건 타입 검사 |
| `SemanticBinder.kt` | 함수·생성자 본문, 스코프, 문장과 제어 흐름, 반환 경로, 필드 초기화 검사 |
| `SemanticExpressionBinder.kt` | 값·생성자 호출·필드·인덱스·이항식의 바인딩 |
| `SemanticCallBinder.kt` | 함수·메서드 호출 대상, 오버로드 선택, 메모리 내장 연산 |

`SemanticContext`는 한 분석 과정의 심볼과 타입 매개변수 환경을 보관한다.
생성자 초기화 상태(`constructing`, `initializedFields`)와 블록 깊이는 `SemanticBinder`만 수정한다.
표현식 바인더는 호출 바인더에 표현식 바인딩 함수만 전달한다. 호출 바인더가 본문 바인더 전체에 접근하지 않는다.
공통 검사 규칙은 `SemanticRules`를 통해 사용하고 각 바인더에 복사하지 않는다.

## IR 변환

| 파일 (`src/main/kotlin/lower/`) | 책임과 수정할 작업 |
| --- | --- |
| `IrLowering.kt` | 모듈·클래스 메타데이터 조립, 합성 생성자와 접근자 |
| `IrFunctionLowering.kt` | 함수 매개변수 설정, 문장·분기·반복문의 명령 변환 |
| `IrExpressionLowering.kt` | 표현식의 값과 명령 생성, 호출·할당·메모리 연산 변환 |
| `IrLoweringRules.kt` | 의미 타입의 IR 표현, 형 변환, 가상 호출과 내장 호출 이름 |
| `FunctionContext.kt` | 함수 하나의 레지스터·지역 변수·라벨·명령 목록 |

함수마다 새 `FunctionContext`를 만든다. 모듈 전체나 전역 객체에 함수별 가변 상태를 두지 않는다.
IR 변환은 이미 결정된 의미를 표현하며 이름 검색이나 새로운 소스 타입 검사를 수행하지 않는다.

## C 출력

| 파일 (`src/main/kotlin/backend/c/`) | 책임과 수정할 작업 |
| --- | --- |
| `CBackend.kt` | 외부 `Backend` 진입점, 출력 요청마다 모듈 출력 객체 생성 |
| `CModuleEmitter.kt` | 파일 조립, 클래스 선언, 객체, 디스패처, C 진입점 |
| `CFunctionEmitter.kt` | 함수 선언과 본문, IR 명령의 C 문장 출력, 주소를 취한 지역 변수 처리 |
| `CRepresentation.kt` | C 타입·식별자·문자열 표기와 객체 타입 태그 경로 |
| `CRuntimeEmitter.kt` | 플랫폼 헤더와 메모리 런타임 소스 |
| `CModuleLayout.kt` | 생성 파일 경로, include 경로, 헤더 가드 |
| `CIntrinsics.kt` | 내장 함수의 C 구현과 의존 모듈 |

모듈 출력 상태는 출력 요청마다, 함수 출력 상태는 함수마다 새로 만든다.
공유 표기 규칙은 `CRepresentation`에 두며 출력기는 소스의 심볼이나 AST에 의존하지 않는다.

## 제네릭 코드 생성

| 파일 (`src/main/kotlin/semantic/`) | 책임 |
| --- | --- |
| `TypeSubstitution.kt` | 타입 매개변수 대입과 상속 경로의 제네릭 타입 인자 전달 |
| `GenericSpecialization.kt` | 사용된 타입 인자별 클래스·메서드 생성과 중복 구체화 방지 |
| `SpecializedBody.kt` | 구체화된 함수 본문의 타입·심볼·호출 대상 복제 |
| `WildcardProjection.kt` | 모든 제네릭의 `*` 호환성과 접근 가능한 메서드·반환형 계산 |

의미 분석에서는 `T`를 유지하며, 바인딩 후 실제 사용된 타입 인자별로 클래스와 메서드를 만든다.
`ArrayIterator<String>`과 `ArrayIterator<Int32>`는 서로 다른 필드·반환형을 갖는 C 클래스와 함수로 출력된다.
생성 이름에는 타입 인자로부터 계산한 접미사가 붙고, 같은 타입 인자 조합은 한 번만 구체화한다.
이는 컴파일 시점의 구체화이며 런타임에 `T`를 임의로 변경하는 기능은 아니다.

대입되지 않은 타입 매개변수가 IR에 도달하면 오류로 처리한다.
배열의 `get`·`set`도 구체화된 메서드를 사용하며, 배열 리터럴은 실제 원소 타입의 버퍼 저장 명령으로 초기화한다.
`examples/generic-specialization.rk`는 여러 원소 타입, 독립 이터레이터, 제네릭 생성자와 객체 원소를 함께 사용하는 검토용 예제다.

## 와일드카드 타입

`*`는 모든 제네릭 클래스와 인터페이스의 타입 인자 위치에서 사용할 수 있다.
`Array<*>`, `Iterator<*>`, `Pair<*, String>`, `Array<Array<*>>`처럼 일부 인자나 중첩 타입에 사용할 수 있다.
`WildcardType`은 실제 타입이 알려지지 않았음을 나타내며, 기존 포인터 와일드카드도 같은 타입을 사용한다.

- `Array<String>`과 `Array<Int32>`는 모두 `Array<*>`로 전달할 수 있다. 반대 방향의 암시적 변환은 허용하지 않는다.
- 알려진 타입 인자는 불변이다. `Pair<*, String>`의 두 번째 인자는 `String`이어야 한다.
- `Array<*>.length()`, `Iterator<*>.hasNext()`처럼 숨겨진 타입과 무관한 연산은 허용한다.
- 알 수 없는 `T`를 직접 읽거나 입력값으로 요구하는 연산은 거부한다. `Array<*>.get()`과 `set()`은 사용할 수 없다.
- 반환형이 또 다른 제네릭 참조라면 안전한 와일드카드 참조로 반환한다. 예를 들어 `Array<*>.iterator()`는 `Iterator<*>`를 반환한다.
- `new Box<*>()`처럼 실제 타입이 지정되지 않은 객체 생성은 거부한다. `Box<Array<*>>`처럼 타입 인자 자체가 완성된 참조 타입이면 생성할 수 있다.
- 중첩 제네릭의 불변성도 유지한다. `Array<Array<String>>`를 `Array<Array<*>>`로 직접 바꿀 수는 없다.

구체화 단계는 와일드카드 참조를 인스턴스화하지 않고 인터페이스와 같은 호출 표로 표현한다.
호출 표는 실제 객체의 타입 태그로 구체화된 구현을 선택하며, 원본 객체의 필드 배치나 원소 저장 타입을 변경하지 않는다.
검토용 예제는 `examples/wildcard-generics.rk`에 있다.
